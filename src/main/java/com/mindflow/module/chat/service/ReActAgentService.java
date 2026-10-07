package com.mindflow.module.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.harness.execute.AgentBudget;
import com.mindflow.harness.execute.ParallelToolRunner;
import com.mindflow.harness.execute.ToolContext;
import com.mindflow.harness.memory.ConversationHistoryCompactor;
import com.mindflow.harness.skill.SkillContextBuffer;
import com.mindflow.harness.skill.SkillIndexFormatter;
import com.mindflow.harness.skill.SkillRegistry;
import com.mindflow.module.chat.agent.AgentToolRegistry;
import com.mindflow.module.memory.service.LongTermMemoryService;
import com.mindflow.module.wiki.service.WikiSearchService;
import com.mindflow.module.chat.handler.ChatStreamingService;
import com.mindflow.runtime.CancellationContext;
import com.mindflow.structure.ai.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class ReActAgentService {

    private static final Logger logger = LoggerFactory.getLogger(ReActAgentService.class);
    private static final int REACT_MAX_COMPLETION_TOKENS = 2000;

    @Value("${mindflow.react.max-rounds:10}")
    private int maxRounds;

    @Value("${mindflow.react.max-tool-calls:8}")
    private int maxToolCalls;

    @Value("${mindflow.react.stagnation-window:3}")
    private int stagnationWindow;

    private final LlmProviderRouter llmProviderRouter;
    private final ParallelToolRunner parallelToolRunner;
    private final ChatSessionManagementService sessionService;
    private final ChatStreamingService streamingService;
    private final ObjectMapper objectMapper;
    private final ReasoningMemoryService reasoningMemoryService;
    private final LongTermMemoryService longTermMemoryService;
    private final SkillRegistry skillRegistry;
    private final SkillContextBuffer skillContextBuffer;
    private final ConversationHistoryCompactor historyCompactor;
    private final WikiSearchService wikiSearchService;
    private final ToolExperienceService toolExperienceService;
    private AgentToolRegistry agentToolRegistry = null;
    public ReActAgentService(LlmProviderRouter llmProviderRouter,
                             ParallelToolRunner parallelToolRunner,
                             ChatSessionManagementService sessionService,
                             ChatStreamingService streamingService,
                             ObjectMapper objectMapper,
                             AgentToolRegistry agentToolRegistry,
                             ToolExperienceService toolExperienceService,
                             ReasoningMemoryService reasoningMemoryService,
                             LongTermMemoryService longTermMemoryService,
                             SkillRegistry skillRegistry,
                             SkillContextBuffer skillContextBuffer,
                             WikiSearchService wikiSearchService) {
        this.llmProviderRouter = llmProviderRouter;
        this.parallelToolRunner = parallelToolRunner;
        this.sessionService = sessionService;
        this.streamingService = streamingService;
        this.objectMapper = objectMapper;
        this.agentToolRegistry = agentToolRegistry;
        this.reasoningMemoryService = reasoningMemoryService;
        this.longTermMemoryService = longTermMemoryService;
        this.skillRegistry = skillRegistry;
        this.skillContextBuffer = skillContextBuffer;
        this.wikiSearchService = wikiSearchService;
        this.toolExperienceService = toolExperienceService;
        this.historyCompactor = new ConversationHistoryCompactor(llmProviderRouter);
    }

    /**
     * 安全执行ReAct循环（带异常捕获）
     */
    public void executeReActLoopSafely(String userId, String userMessage,
                                       String conversationId, String generationId,
                                       List<Map<String, String>> history) {
        try {
            // 初始化多跳推理记忆
            reasoningMemoryService.initialize(generationId, userMessage);
            executeReActLoop(userId, userMessage, conversationId, generationId, history);
        } catch (Exception e) {
            logger.error("ReAct循环执行失败: generationId={}", generationId, e);
            streamingService.markGenerationFailed(generationId, e.getMessage());
            streamingService.handleError(userId, generationId, e);
            streamingService.sendCompletionNotification(userId, generationId, conversationId, true, false);
            streamingService.cleanupGenerationState(generationId, e);
        } finally {
            CancellationContext.clear(generationId);
        }
    }

    /**
     * 执行ReAct决策循环（AgentBudget 驱动：LLM 自主退出 + 停滞检测/硬轮数/token 三重保险阀）
     */
    private void executeReActLoop(String userId, String userMessage,
                                  String conversationId, String generationId,
                                  List<Map<String, String>> history) {
        // 1. 构建初始消息（反馈指导 + 长期记忆 + Skill 索引同一 system 段；已加载 Skill 正文前置到本轮用户消息）
        String skillBodies = skillContextBuffer.drain(userId);
        String effectiveUserMessage = skillBodies.isEmpty()
                ? userMessage
                : skillBodies + "\n\n" + userMessage;
        String skillIndex = SkillIndexFormatter.format(skillRegistry.enabledSkills());
        String wikiContext = buildWikiContext(userMessage, generationId);
        var messages = llmProviderRouter.buildReActMessages(
                effectiveUserMessage, wikiContext, history, buildRecentFeedbackGuidance(userId)
                        + "\n" + longTermMemoryService.buildRelevantContext(userId, userMessage, 5)
                        + (skillIndex.isEmpty() ? "" : "\n" + skillIndex)
                        + toolExperienceGuidance());

        // 2. 预算：默认 token 无硬限（Integer.MAX_VALUE），防死循环交给停滞检测 + 硬轮数
        AgentBudget budget = new AgentBudget(Integer.MAX_VALUE,
                Math.max(2, stagnationWindow),
                Math.max(1, maxRounds));

        int executedToolCalls = 0;
        int round = 0;

        while (true) {
            if (streamingService.isGenerationCancelled(generationId)
                    || CancellationContext.isCancelled(generationId)) {
                return;
            }

            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                logger.warn("ReAct 循环因保险阀退出: reason={}, iteration={}",
                        exitReason, budget.iteration());
                break;
            }

            round = budget.beginIteration();

            // 2.2 调 LLM 前按 TokenBudget 评估并压缩早期对话（防上下文超窗口）
            historyCompactor.compactIfNeeded(messages, compactTriggerTokens());

            streamingService.sendAgentRound(userId, generationId, conversationId, round, "started", 0L, 0, 0);
            long roundStart = System.currentTimeMillis();

            // 2.2 调用LLM获取决策 - 使用阻塞式API
            var turn = completeReActTurnBlocking(userId, conversationId, generationId, messages);
            if (turn == null) {
                streamingService.cleanupGenerationState(generationId, null);
                return;
            }

            streamingService.sendAgentRound(userId, generationId, conversationId, round, "finished",
                    System.currentTimeMillis() - roundStart, turn.promptTokens(), turn.completionTokens());

            budget.recordTokens(turn.promptTokens(), turn.completionTokens(), 0);

            // 2.3 如果没有工具调用，结束循环
            if (turn.toolCalls().isEmpty()) {
                // 注入已积累的推理发现，让LLM做最终综合回答
                injectReasoningContext(messages, generationId, round);
                finalizeResponse(userId, userMessage, conversationId, generationId,
                        budget.totalInputTokens(), budget.totalOutputTokens(), turn);
                return;
            }

            budget.recordToolCalls(turn.toolCalls().stream()
                    .map(tc -> new AgentBudget.ToolCallSignature(tc.name(), String.valueOf(tc.arguments())))
                    .toList());

            // 2.5 执行工具调用（并行批次，结果按入参顺序回灌保证 tool message 配对）
            messages.add(turn.assistantMessage());

            int remainingToolBudget = Math.max(0, maxToolCalls - executedToolCalls);
            List<LlmProviderRouter.ToolCallDecision> executableCalls =
                    turn.toolCalls().size() <= remainingToolBudget
                            ? turn.toolCalls()
                            : turn.toolCalls().subList(0, remainingToolBudget);
            for (int i = executableCalls.size(); i < turn.toolCalls().size(); i++) {
                handleToolBudgetExhausted(userId, generationId, conversationId, turn.toolCalls().get(i));
            }

            for (var toolCall : executableCalls) {
                streamingService.sendToolCallStatus(userId, generationId, conversationId, toolCall, "executing",
                        argsSummary(toolCall.arguments()), null, 0L);
            }

            List<ParallelToolRunner.ToolOutcome> outcomes = parallelToolRunner.executeBatch(
                    new ToolContext(userId, conversationId, generationId), executableCalls,
                    chunk -> streamingService.appendStreamChunk(userId, generationId, conversationId, chunk));

            boolean anyStreamed = false;
            for (int i = 0; i < outcomes.size(); i++) {
                ParallelToolRunner.ToolOutcome outcome = outcomes.get(i);
                var toolCall = executableCalls.get(i);
                executedToolCalls++;
                streamingService.sendToolCallStatus(userId, generationId, conversationId, toolCall,
                        outcome.success() ? "success" : "failed",
                        null, preview(outcome.content(), 300), outcome.elapsedMs());
                reasoningMemoryService.recordToolResult(generationId, round,
                        toolCall.name(), toolCall.arguments(), outcome.content());
                reasoningMemoryService.latestFinding(generationId).ifPresent(finding ->
                        streamingService.sendMemoryFinding(userId, generationId, conversationId,
                                finding.roundNumber(), finding.toolName(), finding.summary()));
                messages.add(toolMessage(toolCall.id(), outcome.content()));
                if (outcome.streamedToUser()) {
                    anyStreamed = true;
                }
            }

            if (anyStreamed) {
                finalizeWithToolStream(userId, userMessage, conversationId, generationId,
                        budget.totalInputTokens(), budget.totalOutputTokens());
                CancellationContext.clear(generationId);
                return;
            }
        }

        CancellationContext.clear(generationId);
        forceFinalAnswer(userId, userMessage, conversationId, generationId, messages,
                budget.totalInputTokens(), budget.totalOutputTokens());
    }

    /**
     * 注入多跳推理上下文到消息列表
     */
    private void injectReasoningContext(List<Map<String, Object>> messages,
                                        String generationId, int currentRound) {
        String context = reasoningMemoryService.buildReasoningContext(generationId);
        if (context.isEmpty()) return;

        // 在系统提示词之后插入推理上下文（位置1，保留原始系统提示词）
        // 如果已经插入过，先移除旧的
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if ("system".equals(msg.get("role"))
                    && msg.get("content") != null
                    && msg.get("content").toString().startsWith("【多跳推理进度】")) {
                messages.remove(i);
                break;
            }
        }

        // 在位置1插入（系统提示词之后）
        Map<String, Object> ctxMsg = new LinkedHashMap<>();
        ctxMsg.put("role", "system");
        ctxMsg.put("content", context);
        messages.add(1, ctxMsg);

        logger.debug("已注入第{}轮推理上下文: generationId={}, findings={}",
                currentRound, generationId, reasoningMemoryService.getFindingCount(generationId));
    }
    /**
     * 阻塞式执行一轮ReAct对话
     */
    private LlmProviderRouter.ReActTurn completeReActTurnBlocking(String userId, String conversationId,
                                                                  String generationId,
                                                                  List<Map<String, Object>> messages) {
        try {
            // 注入当前推理上下文（在调用前，让LLM看到积累的知识）
            injectReasoningContext(messages, generationId,
                    reasoningMemoryService.getFindingCount(generationId) + 1);
            // 调用LLM获取响应 - 使用阻塞API
            var turn = llmProviderRouter.completeReActTurn(
                    userId,
                    messages,
                    agentToolRegistry.getTools(), // tools参数由llmProviderRouter内部处理
                    REACT_MAX_COMPLETION_TOKENS
            );

            // 流式发送助手消息
            if (!turn.content().isBlank()) {
                streamingService.appendStreamChunk(userId, generationId, conversationId, turn.content());
            }

            return turn;
        } catch (Exception e) {
            logger.error("LLM调用失败", e);
            streamingService.markGenerationFailed(generationId, "LLM服务调用失败");
            streamingService.handleError(userId, generationId, e);
            return null;
        }
    }

    /**
     * 构建近期反馈指导
     */
    private String toolExperienceGuidance() {
        try {
            List<String> toolNames = agentToolRegistry.getTools().stream()
                    .map(AgentToolRegistry.AgentTool::name)
                    .toList();
            String guidance = toolExperienceService.buildExperienceGuidance(toolNames);
            return guidance == null || guidance.isBlank() ? "" : "\n" + guidance;
        } catch (Exception e) {
            return "";
        }
    }

    private String buildRecentFeedbackGuidance(String userId) {
        // 从会话管理中获取近期的用户反馈
        return sessionService.buildRecentFeedbackGuidance(userId);
    }

    private int compactTriggerTokens() {
        return llmProviderRouter.contextCompactTriggerTokens();
    }

    private String argsSummary(Map<String, Object> arguments) {
        try {
            return preview(objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments), 200);
        } catch (Exception e) {
            return preview(String.valueOf(arguments), 200);
        }
    }

    private String preview(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }

    private static final int WIKI_REFERENCE_BASE = 100;
    private static final int WIKI_INJECT_TOP_K = 3;

    /**
     * 预检索 Wiki 概念页并注入 system 上下文，同时把每个页面登记为引用（号段 101 起）。
     * 这样 wiki 证据不依赖模型是否主动调用工具，且来源可点击、可持久化。
     */
    private String buildWikiContext(String query, String generationId) {
        try {
            var hits = wikiSearchService.search(query, WIKI_INJECT_TOP_K);
            if (hits.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("【Wiki 概念页】以下结构化概念页与当前问题相关，可直接作为回答依据；")
                    .append("引用时按 (来源#N: 页面标题) 标注，N 使用下方概念页编号。\n");
            int refNumber = WIKI_REFERENCE_BASE;
            boolean firstHit = true;
            for (var hit : hits) {
                refNumber++;
                streamingService.registerReference(generationId, refNumber,
                        new ChatStreamingService.ReferenceInfo(
                                null,
                                hit.title(),
                                null,
                                null,
                                "wiki",
                                "Wiki 概念页",
                                query,
                                hit.markdown(),
                                null,
                                hit.score(),
                                null));
                sb.append("\n【Wiki 概念页 ").append(refNumber).append("】").append(hit.title()).append("\n")
                        .append(hit.markdown()).append("\n");
                if (firstHit) {
                    List<String> related = wikiSearchService.relatedConcepts(hit.title());
                    if (!related.isEmpty()) {
                        sb.append("（关联概念：").append(String.join("、", related))
                                .append("；如需多跳深入，可用 navigate_wiki 跳转到其中任一概念，hops 可设 2）\n");
                    }
                    firstHit = false;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Wiki 预检索失败，跳过注入: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 创建工具消息
     */
    private Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("content", content);
        return message;
    }

    /**
     * 处理工具预算耗尽
     */
    private void handleToolBudgetExhausted(String userId, String generationId,
                                           String conversationId,
                                           LlmProviderRouter.ToolCallDecision toolCall) {
        logger.warn("工具调用次数已达上限，generationId: {}", generationId);
        streamingService.sendToolCallStatus(userId, generationId, conversationId, toolCall, "failed");
    }

    /**
     * 最终确定响应
     */
    private void finalizeResponse(String userId, String userMessage, String conversationId,
                                  String generationId, int promptTokens, int completionTokens,
                                  LlmProviderRouter.ReActTurn turn) {
        logger.info("ReAct循环完成，generationId: {}", generationId);

        // 发送完成通知
        streamingService.sendCompletionNotification(userId, generationId, conversationId, false, false);

        // 清理状态
        streamingService.cleanupGenerationState(generationId, null);
    }

    /**
     * 带工具流的最终确定
     */
    private void finalizeWithToolStream(String userId, String userMessage, String conversationId,
                                        String generationId, int promptTokens, int completionTokens) {
        logger.info("工具流完成，generationId: {}", generationId);
        streamingService.sendCompletionNotification(userId, generationId, conversationId, false, false);
        streamingService.cleanupGenerationState(generationId, null);
    }

    /**
     * 强制生成最终答案
     */
    private void forceFinalAnswer(String userId, String userMessage, String conversationId,
                                  String generationId, List<Map<String, Object>> messages,
                                  int totalPromptTokens, int totalCompletionTokens) {
        logger.info("轮次用尽，强制生成最终答案，generationId: {}", generationId);

        try {
            // 请求LLM生成最终答案 - 再次调用completeReActTurn
            var finalTurn = llmProviderRouter.completeReActTurn(
                    userId,
                    messages,
                    null,
                    REACT_MAX_COMPLETION_TOKENS
            );

            // 流式发送最终答案
            if (!finalTurn.content().isBlank()) {
                streamingService.appendStreamChunk(userId, generationId, conversationId, finalTurn.content());
            }

            // 发送完成通知
            streamingService.sendCompletionNotification(userId, generationId, conversationId, false, false);

        } catch (Exception e) {
            logger.error("强制生成最终答案失败", e);
            streamingService.markGenerationFailed(generationId, "生成最终答案失败");
            streamingService.handleError(userId, generationId, e);
        } finally {
            streamingService.cleanupGenerationState(generationId, null);
        }
    }
}
