package com.mindflow.harness.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.module.chat.agent.AgentToolRegistry;
import com.mindflow.structure.ai.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 子代理 - 可配置角色的轻量 Agent（移植自 MindFlow agent.SubAgent，Web 适配版）。
 *
 * 每个子代理有独立的角色、系统提示词和对话历史；LLM 出口统一走 LlmProviderRouter，
 * 工具调用走 AgentToolExecutor / AgentToolRegistry，流式内容通过 contentSink 推送。
 * 该类的实例不注册为 Spring 单例，由 SubAgentService 按运行创建，避免多用户共享可变历史。
 */
public class SubAgent {

    private static final Logger logger = LoggerFactory.getLogger(SubAgent.class);
    private static final int SUB_AGENT_MAX_COMPLETION_TOKENS = 2000;

    private static final String PLANNER_PROMPT = """
            你是一个任务规划专家。你的职责是分析用户的需求，将其拆解为清晰的执行步骤。

            请按以下 JSON 格式输出执行计划：
            {
                "summary": "任务摘要",
                "steps": [
                    {
                        "id": "step_1",
                        "description": "步骤描述，要具体明确",
                        "type": "ANALYSIS | VERIFICATION | COMMAND | FILE_READ | FILE_WRITE",
                        "dependencies": []
                    }
                ]
            }

            规则：
            1. 每个步骤必须有唯一的 id（如 step_1, step_2）
            2. dependencies 列出依赖的步骤 id
            3. 步骤描述要具体，让执行者能直接理解要做什么
            4. 简单任务可以只拆成 1-3 步
            5. 复杂任务拆成 5-10 步
            6. 不要为了凑步数引入无关操作
            7. 如果多个步骤可以独立完成，不要给它们添加依赖；保持 dependencies 为空
            8. 只有后一步确实需要前一步结果时，才写 dependencies

            只输出 JSON，不要有其他内容。
            请用中文回复。
            """;

    private static final String WORKER_PROMPT = """
            你是一个任务执行专家。你的职责是根据给定的任务步骤，调用合适的工具完成具体操作。

            工具选择原则：
            1. 需要企业/项目/产品等内部知识时，先调用 search_knowledge 检索，再基于片段作答。
            2. 需要整理、总结、归纳知识库内容时，先用 search_knowledge 圈定材料，再调用 generate_summary。
            3. 用户明确表达满意/不满意并希望记录时，调用 submit_feedback。
            4. 仅当用户询问知识库规模、文档数量、更新时间等统计信息时，调用 knowledge_stats。
            5. 用户明确说"记一下/记住/以后记得"时，调用 save_memory 保存精炼稳定事实；一次性请求和临时信息不要保存。
            6. 工具的具体参数以系统提供的 schema 为准。
            7. 同一轮返回多个工具调用时系统会并行执行；工具之间有依赖关系时请分多轮调用。

            如果是 ANALYSIS 或 VERIFICATION 类型任务，请基于上下文直接输出分析结果，不要无意义地调用工具。

            请用中文回复。
            """;

    private static final String REVIEWER_PROMPT = """
            你是一个质量检查专家。你的职责是检查执行结果是否正确、完整和高质量。

            检查要点：
            1. 任务是否按要求完成
            2. 结果是否正确，有无明显错误
            3. 是否遗漏了重要步骤或细节
            4. 输出格式是否规范

            请以 JSON 格式输出检查结果：
            {
                "approved": true 或 false,
                "summary": "检查摘要",
                "issues": ["问题1", "问题2"],
                "suggestions": ["建议1", "建议2"]
            }

            如果 approved 为 true，issues 为空即可。
            如果 approved 为 false，请详细说明问题并给出改进建议。
            只输出 JSON，不要有其他内容。
            请用中文回复。
            """;

    private final String name;
    private final AgentRole role;
    private final String requesterId;
    private final String conversationId;
    private final String generationId;
    private final LlmProviderRouter llmProviderRouter;
    private final AgentToolRegistry toolRegistry;
    private final ParallelToolRunner parallelToolRunner;
    private final ObjectMapper mapper;
    private final Consumer<String> contentSink;
    private final Consumer<String> reasoningSink;
    private final List<Map<String, Object>> conversationHistory = new ArrayList<>();
    private final int maxIterations;
    private final String skillIndex;
    private final com.mindflow.harness.skill.SkillContextBuffer skillContextBuffer;
    private final com.mindflow.harness.memory.ConversationHistoryCompactor historyCompactor;
    private final int compactTriggerTokens;

    public SubAgent(String name,
                    AgentRole role,
                    String requesterId,
                    String conversationId,
                    String generationId,
                    LlmProviderRouter llmProviderRouter,
                    AgentToolRegistry toolRegistry,
                    ParallelToolRunner parallelToolRunner,
                    ObjectMapper mapper,
                    Consumer<String> contentSink,
                    Consumer<String> reasoningSink,
                    int maxIterations,
                    String skillIndex,
                    com.mindflow.harness.skill.SkillContextBuffer skillContextBuffer,
                    com.mindflow.harness.memory.ConversationHistoryCompactor historyCompactor,
                    int compactTriggerTokens) {
        this.name = name;
        this.role = role;
        this.requesterId = requesterId;
        this.conversationId = conversationId;
        this.generationId = generationId;
        this.llmProviderRouter = llmProviderRouter;
        this.toolRegistry = toolRegistry;
        this.parallelToolRunner = parallelToolRunner;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
        this.contentSink = contentSink;
        this.reasoningSink = reasoningSink;
        this.maxIterations = Math.max(1, maxIterations);
        this.skillIndex = skillIndex == null ? "" : skillIndex;
        this.skillContextBuffer = skillContextBuffer;
        this.historyCompactor = historyCompactor;
        this.compactTriggerTokens = compactTriggerTokens;
        this.conversationHistory.add(systemMessage(getSystemPrompt()));
    }

    public String getName() {
        return name;
    }

    public AgentRole getRole() {
        return role;
    }

    public AgentMessage execute(AgentMessage task) {
        return executeWithContext(task, null);
    }

    public AgentMessage executeWithContext(AgentMessage task, String context) {
        logger.info("[{}] 执行任务: type={}, role={}", name, task.type(), role);
        String enrichedContent = task.content();
        if (context != null && !context.isEmpty()) {
            enrichedContent = context + "\n\n当前任务：" + task.content();
        }
        if (skillContextBuffer != null) {
            String skillBodies = skillContextBuffer.drain(requesterId);
            if (!skillBodies.isEmpty()) {
                enrichedContent = skillBodies + "\n\n" + enrichedContent;
            }
        }
        conversationHistory.add(userMessage(enrichedContent));

        AgentBudget budget = new AgentBudget(Integer.MAX_VALUE, 3, maxIterations);

        while (true) {
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String description = budget.describeExit(exitReason);
                logger.warn("[{}] 预算耗尽: reason={}, iteration={}", name, exitReason, budget.iteration());
                return AgentMessage.error(name, role, description);
            }

            budget.beginIteration();

            if (historyCompactor != null) {
                historyCompactor.compactIfNeeded(conversationHistory, compactTriggerTokens);
            }

            try {
                var tools = shouldUseTools() ? toolRegistry.getTools() : null;
                var turn = llmProviderRouter.completeReActTurn(
                        requesterId, conversationHistory, tools, SUB_AGENT_MAX_COMPLETION_TOKENS);

                budget.recordTokens(turn.promptTokens(), turn.completionTokens(), 0);

                if (!turn.toolCalls().isEmpty()) {
                    budget.recordToolCalls(turn.toolCalls().stream()
                            .map(tc -> new AgentBudget.ToolCallSignature(
                                    tc.name(), String.valueOf(tc.arguments())))
                            .toList());
                    conversationHistory.add(turn.assistantMessage());

                    List<ParallelToolRunner.ToolOutcome> outcomes = parallelToolRunner.executeBatch(
                            new ToolContext(requesterId, conversationId, generationId),
                            turn.toolCalls(), contentSink);
                    for (var outcome : outcomes) {
                        conversationHistory.add(toolMessage(outcome.toolCallId(), outcome.content()));
                    }
                    continue;
                }

                if (turn.content() != null && !turn.content().isBlank()) {
                    conversationHistory.add(assistantMessage(turn.content()));
                    if (contentSink != null) {
                        contentSink.accept(turn.content());
                    }
                }
                return AgentMessage.result(name, role, turn.content());
            } catch (Exception e) {
                logger.error("[{}] LLM 调用失败", name, e);
                return AgentMessage.error(name, role, "LLM 调用失败: " + e.getMessage());
            }
        }
    }

    public AgentMessage review(String originalTask, String executionResult) {
        String reviewInput = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
        return execute(AgentMessage.task("orchestrator", reviewInput));
    }

    public void clearHistory() {
        Map<String, Object> systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);
    }

    private boolean shouldUseTools() {
        return role == AgentRole.WORKER;
    }

    private String getSystemPrompt() {
        String base = switch (role) {
            case PLANNER -> PLANNER_PROMPT;
            case WORKER -> WORKER_PROMPT;
            case REVIEWER -> REVIEWER_PROMPT;
        };
        return skillIndex.isEmpty() ? base : base + "\n" + skillIndex;
    }

    private Map<String, Object> systemMessage(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "system");
        message.put("content", content);
        return message;
    }

    private Map<String, Object> userMessage(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", content);
        return message;
    }

    private Map<String, Object> assistantMessage(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content == null ? "" : content);
        return message;
    }

    private Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("content", content == null ? "" : content);
        return message;
    }
}
