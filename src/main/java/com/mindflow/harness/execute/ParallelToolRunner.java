package com.mindflow.harness.execute;

import com.mindflow.harness.hitl.HitlToolRegistry;
import com.mindflow.module.chat.agent.AgentToolRegistry;
import com.mindflow.structure.ai.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 并行工具执行器（承接 MindFlow ToolRegistry.executeTools 语义）：
 * 并发上限 mindflow.tool.parallelism（默认 4），批次超时 90s，
 * 结果按入参顺序返回以保证 tool message 配对协议；单工具失败不中断整批。
 * 工具一律经 HitlToolRegistry（L2）→ AgentToolRegistry（L1），这是设计 §7.1 的唯一入口约束。
 */
@Service
public class ParallelToolRunner {

    private static final Logger logger = LoggerFactory.getLogger(ParallelToolRunner.class);

    private final HitlToolRegistry hitlToolRegistry;
    private final AgentToolRegistry toolRegistry;
    private final com.mindflow.module.chat.service.ToolExperienceService toolExperienceService;

    @Value("${mindflow.tool.parallelism:4}")
    private int parallelism;

    @Value("${mindflow.tool.batch-timeout-seconds:90}")
    private long batchTimeoutSeconds;

    public ParallelToolRunner(HitlToolRegistry hitlToolRegistry, AgentToolRegistry toolRegistry,
                              com.mindflow.module.chat.service.ToolExperienceService toolExperienceService) {
        this.hitlToolRegistry = hitlToolRegistry;
        this.toolRegistry = toolRegistry;
        this.toolExperienceService = toolExperienceService;
    }

    public record ToolOutcome(
            String toolCallId,
            String name,
            boolean success,
            String content,
            boolean streamedToUser,
            long elapsedMs
    ) {}

    public List<ToolOutcome> executeBatch(ToolContext ctx,
                                          List<LlmProviderRouter.ToolCallDecision> calls,
                                          Consumer<String> chunkSink) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }

        Set<String> available = toolRegistry.getTools().stream()
                .map(AgentToolRegistry.AgentTool::name)
                .collect(Collectors.toSet());

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(calls.size(), Math.max(1, parallelism)), r -> {
                    Thread t = new Thread(r, "mindflow-tool-exec");
                    t.setDaemon(true);
                    return t;
                });

        try {
            List<CompletableFuture<ToolOutcome>> futures = new ArrayList<>();
            for (LlmProviderRouter.ToolCallDecision call : calls) {
                if (!available.contains(call.name())) {
                    futures.add(CompletableFuture.completedFuture(new ToolOutcome(
                            call.id(), call.name(), false,
                            "未知工具: " + call.name() + "。可用工具: " + String.join(", ", available),
                            false, 0L)));
                    continue;
                }
                futures.add(CompletableFuture.supplyAsync(() -> {
                    long toolStart = System.currentTimeMillis();
                    try {
                        var result = hitlToolRegistry.executeTool(ctx, call.name(), call.arguments(), chunkSink);
                        return new ToolOutcome(call.id(), call.name(), result.success(),
                                result.content(), result.streamedToUser(),
                                System.currentTimeMillis() - toolStart);
                    } catch (Exception e) {
                        logger.warn("工具执行失败: name={}, error={}", call.name(), e.getMessage());
                        return new ToolOutcome(call.id(), call.name(), false,
                                "工具执行失败: " + e.getMessage(), false,
                                System.currentTimeMillis() - toolStart);
                    }
                }, executor));
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(Math.max(1, batchTimeoutSeconds), TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                logger.warn("工具批次超时({}s)，取消剩余任务", batchTimeoutSeconds);
                futures.forEach(f -> f.cancel(true));
            } catch (Exception e) {
                logger.warn("工具批次等待异常: {}", e.getMessage());
            }

            List<ToolOutcome> outcomes = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                LlmProviderRouter.ToolCallDecision call = calls.get(i);
                try {
                    outcomes.add(futures.get(i).get(1, TimeUnit.SECONDS));
                } catch (Exception e) {
                    outcomes.add(new ToolOutcome(call.id(), call.name(), false,
                            "工具执行超时（" + batchTimeoutSeconds + "秒），已取消", false,
                            batchTimeoutSeconds * 1000L));
                }
            }
            recordFailureExperiences(outcomes);
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 失败即经验：批次在这里收敛，因此未知工具 / 执行异常 / 批次超时三类失败都能被捕获。
     */
    private void recordFailureExperiences(List<ToolOutcome> outcomes) {
        for (ToolOutcome outcome : outcomes) {
            if (outcome == null || outcome.success()) {
                continue;
            }
            try {
                toolExperienceService.recordFailure(outcome.name(), outcome.content());
            } catch (Exception e) {
                logger.debug("记录工具失败经验跳过: {}", e.getMessage());
            }
        }
    }
}
