package com.mindflow.harness.execute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.module.chat.handler.ChatStreamingService;
import com.mindflow.runtime.CancellationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Multi-Agent 编排器（移植自 MindFlow agent.AgentOrchestrator，Web 化）。
 * 主从架构：规划者拆解 → 执行者执行 → 检查者审查（最多 2 次重试），批次内并行。
 * 终端 System.out 改为 ChatStreamingService 的 team_event / chunk 推送。
 */
@Service
public class MultiAgentService {

    private static final Logger logger = LoggerFactory.getLogger(MultiAgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES_PER_STEP = 2;
    private static final int MAX_DECISION_RETRIES = 2;

    public enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public record ExecutionStep(String id, String description, String type,
                                List<String> dependencies, String result,
                                StepStatus status) {
        public static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return new ExecutionStep(id, description, type, dependencies, null, StepStatus.PENDING);
        }

        public ExecutionStep withResult(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.COMPLETED);
        }

        public ExecutionStep withFailed(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.FAILED);
        }

        public ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.RUNNING);
        }
    }

    private final SubAgentService subAgentService;
    private final ChatStreamingService streamingService;
    private final FailureDecisionService failureDecisionService;

    public MultiAgentService(SubAgentService subAgentService,
                             ChatStreamingService streamingService,
                             FailureDecisionService failureDecisionService) {
        this.subAgentService = subAgentService;
        this.streamingService = streamingService;
        this.failureDecisionService = failureDecisionService;
    }

    public String run(String userId, String generationId, String conversationId, String goal,
                      List<Map<String, String>> history) {
        String historyContext = com.mindflow.harness.memory.HistoryDigest.render(history);
        logger.info("Multi-Agent 执行开始: generationId={}, goal={}, historyTurns={}",
                generationId, goal, history == null ? 0 : history.size());
        try {
            chatEvent(userId, generationId, conversationId, "planning", "PLANNER", "planner", null,
                    "规划者正在分析任务...");
            SubAgent planner = subAgentService.newSubAgent("planner", AgentRole.PLANNER,
                    userId, conversationId, generationId, null, null);
            String planInput = historyContext.isBlank() ? goal : historyContext + "\n\n当前请求：\n" + goal;
            AgentMessage planResult = planner.execute(
                    AgentMessage.task("orchestrator", "请为以下任务制定执行计划：\n" + planInput));
            planner.clearHistory();

            if (isCancelled(generationId)) {
                return "⏹️ 已取消当前多 Agent 任务。";
            }
            if (planResult.type() == AgentMessage.Type.ERROR) {
                String detail = "规划阶段失败，规划者 LLM 调用出错：" + planResult.content();
                sendTeamFailure(userId, generationId, conversationId, "team_planner", detail);
                return "❌ " + detail;
            }
            if (planResult.content() == null || planResult.content().isBlank()) {
                String detail = "规划失败：规划者未能生成有效计划";
                sendTeamFailure(userId, generationId, conversationId, "team_planner", detail);
                return "❌ " + detail;
            }

            List<ExecutionStep> steps = parsePlan(planResult.content());
            if (steps.isEmpty()) {
                String detail = "规划失败：无法解析执行计划";
                sendTeamFailure(userId, generationId, conversationId, "team_planner", detail);
                return "❌ " + detail + "\n原始输出:\n" + planResult.content();
            }

            List<SubAgent> workers = List.of(
                    subAgentService.newSubAgent("worker-1", AgentRole.WORKER,
                            userId, conversationId, generationId, null, null),
                    subAgentService.newSubAgent("worker-2", AgentRole.WORKER,
                            userId, conversationId, generationId, null, null));

            Map<String, Integer> retryCount = new ConcurrentHashMap<>();
            Map<String, Integer> decisionRetries = new ConcurrentHashMap<>();
            int singleStepCursor = 0;

            while (true) {
                if (isCancelled(generationId)) {
                    return "⏹️ 已取消当前多 Agent 任务。";
                }
                List<ExecutionStep> executable = getExecutableSteps(steps);
                if (executable.isEmpty()) {
                    break;
                }
                List<String> batchIds = executable.stream().map(ExecutionStep::id).toList();

                if (executable.size() == 1) {
                    ExecutionStep step = executable.get(0);
                    SubAgent worker = workers.get(singleStepCursor % workers.size());
                    singleStepCursor++;
                    SubAgent reviewer = subAgentService.newSubAgent(
                            "reviewer", AgentRole.REVIEWER, userId, conversationId, generationId, null, null);
                    runStep(userId, generationId, conversationId, steps, retryCount,
                            step, worker, reviewer, withHistory(historyContext, buildStepContext(steps, step)));
                    worker.clearHistory();
                } else {
                    runBatchParallel(userId, generationId, conversationId, steps, retryCount, executable, workers,
                            historyContext);
                }

                List<ExecutionStep> failedSteps = steps.stream()
                        .filter(step -> batchIds.contains(step.id()) && step.status() == StepStatus.FAILED)
                        .toList();
                if (failedSteps.isEmpty()) {
                    continue;
                }

                List<ExecutionStep> retryableSteps = failedSteps.stream()
                        .filter(step -> decisionRetries.getOrDefault(step.id(), 0) < MAX_DECISION_RETRIES)
                        .toList();
                FailureDecisionService.Action decision = requestTeamFailureDecision(
                        userId, generationId, conversationId, failedSteps, retryableSteps);

                if (decision == FailureDecisionService.Action.ABORT) {
                    chatEvent(userId, generationId, conversationId, "execution", null, null, null,
                            "⏹️ 已按你的选择终止多 Agent 任务");
                    return "⏹️ 已按你的选择终止多 Agent 任务。";
                }
                if (decision == FailureDecisionService.Action.RETRY) {
                    for (ExecutionStep step : retryableSteps) {
                        decisionRetries.merge(step.id(), 1, Integer::sum);
                        updateStep(steps, step.id(), ExecutionStep.pending(
                                step.id(), step.description(), step.type(), step.dependencies()));
                    }
                    chatEvent(userId, generationId, conversationId, "execution", null, null, null,
                            "🔄 按你的选择重试 " + retryableSteps.size() + " 个失败步骤");
                }
            }

            for (ExecutionStep step : steps) {
                if (step.status() == StepStatus.PENDING) {
                    chatEvent(userId, generationId, conversationId, "execution", null, null, step.id(),
                            "⏭️ 步骤 [" + step.id() + "] 因前置步骤失败被跳过: " + step.description());
                }
            }

            return buildFinalResult(steps);
        } catch (Exception e) {
            logger.error("Multi-Agent 执行失败: generationId={}", generationId, e);
            String detail = "多 Agent 协作任务执行失败: " + e.getMessage();
            sendTeamFailure(userId, generationId, conversationId, "team", detail);
            return "❌ " + detail;
        }
    }

    List<ExecutionStep> parsePlan(String planJson) {
        try {
            String cleaned = planJson.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();

            JsonNode root = MAPPER.readTree(cleaned);
            JsonNode stepsNode = root.path("steps");

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                stepsNode = root.path("tasks");
            }

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                logger.warn("Plan JSON has no 'steps' or 'tasks' array");
                return List.of();
            }

            List<ExecutionStep> steps = new ArrayList<>();
            Map<String, String> idMapping = new HashMap<>();
            int stepIndex = 1;

            for (JsonNode stepNode : stepsNode) {
                String originalId = stepNode.path("id").asText();
                String newId = "step_" + stepIndex++;
                idMapping.put(originalId, newId);

                String description = stepNode.path("description").asText();
                String type = stepNode.path("type").asText("COMMAND");
                steps.add(ExecutionStep.pending(newId, description, type, new ArrayList<>()));
            }

            stepIndex = 1;
            for (JsonNode stepNode : stepsNode) {
                String newId = "step_" + stepIndex++;
                JsonNode depsNode = stepNode.path("dependencies");
                if (depsNode.isArray()) {
                    List<String> deps = new ArrayList<>();
                    for (JsonNode dep : depsNode) {
                        deps.add(idMapping.getOrDefault(dep.asText(), dep.asText()));
                    }
                    int idx = stepIndex - 2;
                    if (idx >= 0 && idx < steps.size()) {
                        ExecutionStep old = steps.get(idx);
                        steps.set(idx, new ExecutionStep(old.id(), old.description(), old.type(),
                                deps, old.result(), old.status()));
                    }
                }
            }

            return steps;
        } catch (Exception e) {
            logger.error("Failed to parse plan JSON", e);
            return List.of();
        }
    }

    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> step.dependencies().stream()
                        .allMatch(dep -> statusMap.get(dep) == StepStatus.COMPLETED))
                .toList();
    }

    boolean parseReviewApproval(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            logger.warn("Reviewer returned empty content, defaulting to rejected");
            return false;
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = MAPPER.readTree(cleaned);
            JsonNode approvedNode = root.path("approved");
            if (approvedNode.isMissingNode() || approvedNode.isNull()) {
                logger.warn("Reviewer JSON missing 'approved' field, defaulting to rejected");
                return false;
            }
            return approvedNode.asBoolean(false);
        } catch (Exception e) {
            String lower = reviewContent.toLowerCase();
            boolean hasNegativeKeyword = lower.contains("未通过") || lower.contains("不通过")
                    || lower.contains("不合格") || lower.contains("有问题")
                    || lower.contains("\"approved\": false") || lower.contains("\"approved\":false");
            boolean hasPositiveKeyword = lower.contains("通过") || lower.contains("合格")
                    || lower.contains("\"approved\": true") || lower.contains("\"approved\":true");
            if (hasNegativeKeyword) {
                return false;
            }
            if (!hasPositiveKeyword) {
                logger.warn("Reviewer output unparseable and contains no explicit approval, defaulting to rejected");
                return false;
            }
            return true;
        }
    }

    String parseReviewIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            return "";
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = MAPPER.readTree(cleaned);

            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isArray() && !issuesNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode issue : issuesNode) {
                    sb.append("- ").append(issue.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            JsonNode suggestionsNode = root.path("suggestions");
            if (suggestionsNode.isArray() && !suggestionsNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode suggestion : suggestionsNode) {
                    sb.append("- ").append(suggestion.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            String summary = root.path("summary").asText();
            if (!summary.isEmpty()) {
                return summary;
            }
        } catch (Exception ignored) {
        }
        return "审查未通过，请改进执行结果";
    }

    private void runBatchParallel(String userId, String generationId, String conversationId,
                                  List<ExecutionStep> steps, Map<String, Integer> retryCount,
                                  List<ExecutionStep> batch, List<SubAgent> workers,
                                  String historyContext) {
        int parallelism = Math.min(batch.size(), workers.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "mindflow-multi-agent");
            t.setDaemon(true);
            return t;
        });
        BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (ExecutionStep step : batch) {
                String context = withHistory(historyContext, buildStepContext(steps, step));
                futures.add(executor.submit(() -> {
                    SubAgent worker = null;
                    SubAgent reviewer = subAgentService.newSubAgent(
                            "reviewer-" + step.id(), AgentRole.REVIEWER,
                            userId, conversationId, generationId, null, null);
                    try {
                        worker = workerPool.take();
                        runStep(userId, generationId, conversationId, steps, retryCount,
                                step, worker, reviewer, context);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                    } catch (RuntimeException e) {
                        logger.error("Parallel step {} failed unexpectedly", step.id(), e);
                        updateStep(steps, step.id(), step.withFailed("并行执行异常: " + e.getMessage()));
                    } finally {
                        if (worker != null) {
                            worker.clearHistory();
                            workerPool.offer(worker);
                        }
                    }
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    logger.error("Parallel step task failed", e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void runStep(String userId, String generationId, String conversationId,
                         List<ExecutionStep> steps, Map<String, Integer> retryCount,
                         ExecutionStep step, SubAgent worker, SubAgent reviewer, String context) {
        chatEvent(userId, generationId, conversationId, "execution", "WORKER", worker.getName(), step.id(),
                worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());

        if (isCancelled(generationId)) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            return;
        }

        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = worker.executeWithContext(taskMsg, context);
        if (isCancelled(generationId)) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            return;
        }

        if (result.type() == AgentMessage.Type.ERROR) {
            updateStep(steps, step.id(), step.withFailed(result.content()));
            chatEvent(userId, generationId, conversationId, "execution", "WORKER", worker.getName(), step.id(),
                    "❌ 步骤 [" + step.id() + "] 执行失败：" + result.content());
            return;
        }
        if (result.content() == null || result.content().isBlank()) {
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            return;
        }

        chatEvent(userId, generationId, conversationId, "review", "REVIEWER", reviewer.getName(), step.id(),
                reviewer.getName() + " 正在审查步骤 [" + step.id() + "] 的结果...");
        AgentMessage reviewResult = reviewer.review(step.description(), result.content());
        reviewer.clearHistory();

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            updateStep(steps, step.id(), step.withResult(result.content()));
            return;
        }

        boolean approved = parseReviewApproval(reviewResult.content());
        String acceptedResult = result.content();

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            chatEvent(userId, generationId, conversationId, "review", "REVIEWER", reviewer.getName(), step.id(),
                    "✅ 步骤 [" + step.id() + "] 审查通过");
            return;
        }

        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = parseReviewIssues(reviewResult.content());

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retries++;
            retryCount.put(step.id(), retries);

            String feedbackContext = context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues;
            AgentMessage retryResult = worker.executeWithContext(taskMsg, feedbackContext);
            if (retryResult.type() == AgentMessage.Type.ERROR) {
                issues = "重试时 LLM 调用失败：" + retryResult.content();
                approved = false;
                continue;
            }
            if (retryResult.content() == null || retryResult.content().isBlank()) {
                acceptedResult = "执行结果为空";
                approved = false;
                issues = "执行结果为空";
                continue;
            }

            acceptedResult = retryResult.content();
            AgentMessage retryReview = reviewer.review(step.description(), acceptedResult);
            reviewer.clearHistory();

            if (retryReview.type() == AgentMessage.Type.ERROR) {
                approved = true;
                issues = "";
                break;
            }

            approved = parseReviewApproval(retryReview.content());
            issues = parseReviewIssues(retryReview.content());
        }

        updateStep(steps, step.id(), step.withResult(acceptedResult));
        chatEvent(userId, generationId, conversationId, "review", "REVIEWER", reviewer.getName(), step.id(),
                approved
                        ? "✅ 步骤 [" + step.id() + "] 重试后审查通过"
                        : "⚠️ 步骤 [" + step.id() + "] 超过最大重试次数，保留当前结果");
    }

    private synchronized void updateStep(List<ExecutionStep> steps, String stepId, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        StringBuilder context = new StringBuilder();
        context.append("总任务上下文：\n");

        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && currentStep.dependencies().contains(step.id())) {
                context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                        .append(step.description()).append("\n");
                if (step.result() != null && !step.result().isBlank()) {
                    String preview = step.result().length() > 500
                            ? step.result().substring(0, 500) + "..."
                            : step.result();
                    context.append("结果：").append(preview).append("\n");
                }
                context.append("\n");
            }
        }

        return context.toString();
    }

    private String buildFinalResult(List<ExecutionStep> steps) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
        boolean hasFailedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);

        if (allCompleted) {
            result.append("✅ 多 Agent 协作任务完成！\n\n");
        } else if (hasFailedSteps) {
            result.append("⚠️ 多 Agent 协作任务未完全完成，存在失败步骤。\n\n");
        } else {
            result.append("⚠️ 多 Agent 协作任务部分完成，仍有未执行步骤。\n\n");
        }
        result.append("📋 执行总结：\n");

        for (ExecutionStep step : steps) {
            result.append("[").append(step.id()).append("] ");
            if (step.status() == StepStatus.COMPLETED) {
                result.append("✅ ");
            } else if (step.status() == StepStatus.FAILED) {
                result.append("❌ ");
            } else {
                result.append("⏳ ");
            }
            result.append(step.description()).append("\n");

            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 120
                        ? step.result().substring(0, 120) + "..."
                        : step.result();
                result.append("   结果：").append(preview).append("\n");
            }
        }

        return result.toString();
    }

    private void sendTeamFailure(String userId, String generationId, String conversationId,
                                 String scope, String detail) {
        streamingService.sendNotice(userId, generationId, conversationId, "error", scope,
                "多 Agent 任务失败", detail, false);
    }

    private FailureDecisionService.Action requestTeamFailureDecision(String userId, String generationId,
                                                                     String conversationId,
                                                                     List<ExecutionStep> failedSteps,
                                                                     List<ExecutionStep> retryableSteps) {
        if (retryableSteps.isEmpty()) {
            for (ExecutionStep step : failedSteps) {
                sendTeamFailure(userId, generationId, conversationId, "team_step",
                        "步骤 [" + step.id() + "]（" + step.description() + "）失败：" + step.result()
                                + "；已达到重试上限，结果不计入最终答案。");
            }
            return FailureDecisionService.Action.SKIP;
        }

        List<Map<String, Object>> stepDtos = new ArrayList<>();
        for (ExecutionStep step : retryableSteps) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", step.id());
            dto.put("description", step.description());
            dto.put("error", step.result());
            stepDtos.add(dto);
        }

        String decisionId = generationId + "-team-failure-" + System.nanoTime();
        failureDecisionService.register(decisionId);
        streamingService.sendFailureDecision(userId, generationId, conversationId, decisionId,
                stepDtos, failureDecisionService.timeoutSeconds(),
                List.of("retry", "skip", "abort"), "team_step");
        logger.info("已请求 Team 失败决策: decisionId={}, steps={}", decisionId, stepDtos.size());
        return failureDecisionService.await(decisionId);
    }

    private void chatEvent(String userId, String generationId, String conversationId,
                           String phase, String role, String workerName, String stepId, String message) {
        streamingService.sendTeamEvent(userId, generationId, conversationId,
                phase, role, workerName, stepId, message);
    }

    private static String withHistory(String historyContext, String context) {
        return historyContext == null || historyContext.isBlank() ? context : historyContext + "\n\n" + context;
    }

    private boolean isCancelled(String generationId) {
        return streamingService.isGenerationCancelled(generationId)
                || CancellationContext.isCancelled(generationId);
    }
}
