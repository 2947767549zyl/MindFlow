package com.mindflow.module.chat.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.common.exception.RateLimitExceededException;
import com.mindflow.module.chat.service.ChatGenerationStateService;
import com.mindflow.module.chat.service.ChatPersistenceService;
import com.mindflow.structure.ai.LlmProviderRouter;
import com.mindflow.runtime.CancellationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatStreamingService {

    private static final Logger logger = LoggerFactory.getLogger(ChatStreamingService.class);

    private final ChatGenerationStateService stateService;
    private final ChatSessionRegistry sessionRegistry;
    private final ChatPersistenceService persistenceService;
    private final com.mindflow.module.chat.service.AgentTraceCodec agentTraceCodec;
    private final Map<String, List<Map<String, Object>>> generationTraces = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 响应构建器
    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    // 停止标志
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();
    // 活跃的LLM流
    private final Map<String, LlmProviderRouter.StreamHandle> activeStreams = new ConcurrentHashMap<>();
    // 引用映射
    private final Map<String, Map<Integer, ReferenceInfo>> generationReferenceMappings = new ConcurrentHashMap<>();

    public ChatStreamingService(ChatGenerationStateService stateService,
                                ChatSessionRegistry sessionRegistry,
                                ChatPersistenceService persistenceService,
                                com.mindflow.module.chat.service.AgentTraceCodec agentTraceCodec) {
        this.stateService = stateService;
        this.sessionRegistry = sessionRegistry;
        this.persistenceService = persistenceService;
        this.agentTraceCodec = agentTraceCodec;
    }

    private void recordTrace(String generationId, Map<String, Object> payload) {
        if (generationId == null || payload == null) {
            return;
        }
        generationTraces
                .computeIfAbsent(generationId, key -> Collections.synchronizedList(new ArrayList<>()))
                .add(new LinkedHashMap<>(payload));
    }

    private void sendTraced(String userId, String generationId, Map<String, Object> payload) {
        recordTrace(generationId, payload);
        sessionRegistry.sendJsonToUser(userId, payload);
    }

    /**
     * 创建生成任务
     */
    public String createGeneration(String userId, String conversationId, String userMessage) {
        var generation = stateService.createGeneration(userId, conversationId, userMessage);
        responseBuilders.put(generation.generationId(), new StringBuilder());
        return generation.generationId();
    }

    /**
     * 发送生成开始信号
     */
    public void sendGenerationStart(String userId, String generationId, String conversationId) {
        sessionRegistry.sendJsonToUser(userId, Map.of(
                "type", "start",
                "generationId", generationId,
                "conversationId", conversationId,
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 追加流式片段
     */
    public void appendStreamChunk(String userId, String generationId,
                                  String conversationId, String chunk) {
        if (chunk == null || chunk.isEmpty() || isGenerationCancelled(generationId)) {
            return;
        }

        StringBuilder builder = responseBuilders.get(generationId);
        if (builder != null) {
            builder.append(chunk);
        }

        stateService.appendChunk(generationId, chunk);
        sendResponseChunk(userId, generationId, conversationId, chunk);
    }

    /**
     * 发送响应片段
     */
    private void sendResponseChunk(String userId, String generationId,
                                   String conversationId, String chunk) {
        sessionRegistry.sendJsonToUser(userId, Map.of(
                "type", "chunk",
                "generationId", generationId,
                "conversationId", conversationId,
                "chunk", chunk
        ));
    }
    /**
     * 发送工具调用状态
     */
    public void sendToolCallStatus(String userId, String generationId, String conversationId,
                                   LlmProviderRouter.ToolCallDecision toolCall, String status) {
        sendToolCallStatus(userId, generationId, conversationId, toolCall, status, null, null, 0L);
    }

    /**
     * 发送工具调用状态（含参数摘要 / 结果预览 / 耗时，供前端"执行过程"面板展示）
     */
    public void sendToolCallStatus(String userId, String generationId, String conversationId,
                                   LlmProviderRouter.ToolCallDecision toolCall, String status,
                                   String argumentsSummary, String resultPreview, long elapsedMs) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "tool_call");
        payload.put("tool", toolCall.name());
        payload.put("toolCallId", toolCall.id());
        payload.put("status", status);
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("timestamp", System.currentTimeMillis());
        if (argumentsSummary != null && !argumentsSummary.isBlank()) {
            payload.put("arguments", argumentsSummary);
        }
        if (resultPreview != null && !resultPreview.isBlank()) {
            payload.put("resultPreview", resultPreview);
        }
        if (elapsedMs > 0) {
            payload.put("elapsedMs", elapsedMs);
        }
        sendTraced(userId, generationId, payload);
    }

    /**
     * 推送 Agent 轮次事件（每轮 LLM 调用的开始/结束 + 耗时 + token）
     */
    public void sendAgentRound(String userId, String generationId, String conversationId,
                               int round, String phase, long elapsedMs,
                               int promptTokens, int completionTokens) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "agent_round");
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("round", round);
        payload.put("phase", phase);
        payload.put("elapsedMs", elapsedMs);
        payload.put("promptTokens", promptTokens);
        payload.put("completionTokens", completionTokens);
        payload.put("timestamp", System.currentTimeMillis());
        sendTraced(userId, generationId, payload);
    }

    /**
     * 推送多跳推理发现事件
     */
    public void sendMemoryFinding(String userId, String generationId, String conversationId,
                                  int round, String tool, String summary) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "memory_finding");
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("round", round);
        payload.put("tool", tool);
        payload.put("summary", summary);
        payload.put("timestamp", System.currentTimeMillis());
        sendTraced(userId, generationId, payload);
    }


    /**
     * 发送完成通知
     */
    public void sendCompletionNotification(String userId, String generationId,
                                           String conversationId, boolean failed,
                                           boolean persistenceDegraded) {
        boolean persistFailed = !failed && !persistCompletedConversation(userId, generationId, conversationId);

        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "completion");
        notification.put("generationId", generationId);
        notification.put("conversationId", conversationId);
        notification.put("status", failed ? "failed" : "finished");
        notification.put("message", failed ? "响应已中断" : "响应已完成");
        notification.put("timestamp", System.currentTimeMillis());
        notification.put("date", LocalDateTime.now().toString());

        if (!failed) {
            Map<Integer, ReferenceInfo> referenceMappings = generationReferenceMappings.get(generationId);
            if (referenceMappings != null && !referenceMappings.isEmpty()) {
                notification.put("referenceMappings", toSerializableReferenceMappings(referenceMappings));
            }
        }

        if (persistenceDegraded || persistFailed) {
            notification.put("persistenceDegraded", true);
            notification.put("persistenceWarning", "本次回复未能持久化到数据库，刷新后可能无法在历史中找到。");
        }

        sessionRegistry.sendJsonToUser(userId, notification);
    }

    private boolean persistCompletedConversation(String userId, String generationId, String conversationId) {
        try {
            StringBuilder accumulated = responseBuilders.get(generationId);
            String answer = accumulated == null ? "" : accumulated.toString();
            String question = stateService.getGeneration(generationId)
                    .map(ChatGenerationStateService.GenerationSnapshot::question)
                    .orElse(null);

            // 无内容可持久化（如被取消的空回答）视为无需落库，不标记为持久化失败
            if (question == null || question.isBlank() || answer.isBlank()) {
                return true;
            }

            Map<Integer, ReferenceInfo> mappings = generationReferenceMappings.get(generationId);
            String traceJson = agentTraceCodec.toJson(generationTraces.get(generationId));
            boolean persisted = persistenceService.persistConversation(
                    userId, question, answer, conversationId, mappings, traceJson);
            if (persisted) {
                persistenceService.updateRedisHistory(conversationId, question, answer, mappings);
            }
            return persisted;
        } catch (Exception e) {
            logger.warn("持久化对话失败: generationId={}, error={}", generationId, e.getMessage());
            return false;
        }
    }

    /**
     * 发送限流消息
     */
    public void sendRateLimitMessage(String userId, String generationId,
                                     RateLimitExceededException exception) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "error");
        payload.put("generationId", generationId);
        payload.put("code", 429);
        payload.put("message", exception.getMessage());
        payload.put("retryAfterSeconds", exception.getRetryAfterSeconds());
        sessionRegistry.sendJsonToUser(userId, payload);
    }

    /**
     * 发送错误消息
     */
    public void handleError(String userId, String generationId, Throwable error) {
        logger.error("AI服务错误", error);
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("type", "error");
        errorResponse.put("generationId", generationId);
        errorResponse.put("error", "AI 服务调用失败：" + deepestMessage(error));
        sessionRegistry.sendJsonToUser(userId, errorResponse);
    }

    /**
     * 推送失败通知：仅限"影响最终结果、需要用户知晓"的失败（能自愈的工具失败不走这里，避免告警疲劳）。
     * 刻意不写入 generationTraces——历史回放时不应重新弹出通知。
     */
    public void sendNotice(String userId, String generationId, String conversationId,
                           String level, String scope, String title, String detail, boolean retryable) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "notice");
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("level", level);
        payload.put("scope", scope);
        payload.put("title", title);
        payload.put("detail", detail);
        payload.put("retryable", retryable);
        payload.put("timestamp", System.currentTimeMillis());
        sessionRegistry.sendJsonToUser(userId, payload);
    }

    public void sendFailureDecision(String userId, String generationId, String conversationId,
                                    String decisionId, List<Map<String, Object>> tasks, long timeoutSeconds) {
        sendFailureDecision(userId, generationId, conversationId, decisionId, tasks, timeoutSeconds,
                List.of("retry", "replan", "skip", "abort"), "plan_task");
    }

    public void sendFailureDecision(String userId, String generationId, String conversationId,
                                    String decisionId, List<Map<String, Object>> tasks, long timeoutSeconds,
                                    List<String> options, String scope) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "failure_decision");
        payload.put("decisionId", decisionId);
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("tasks", tasks);
        payload.put("options", options);
        payload.put("scope", scope);
        payload.put("timeoutSeconds", timeoutSeconds);
        payload.put("timestamp", System.currentTimeMillis());
        sessionRegistry.sendJsonToUser(userId, payload);
    }

    public void sendTaskClarification(String userId, String generationId, String conversationId,
                                      String clarificationId, String taskId, String description,
                                      List<Map<String, Object>> options, long timeoutSeconds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "task_clarification");
        payload.put("clarificationId", clarificationId);
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("taskId", taskId);
        payload.put("description", description);
        payload.put("options", options);
        payload.put("timeoutSeconds", timeoutSeconds);
        payload.put("timestamp", System.currentTimeMillis());
        sessionRegistry.sendJsonToUser(userId, payload);
    }

    private String deepestMessage(Throwable error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message == null ? "未知错误" : message;
    }

    /**
     * 标记生成失败
     */
    public void markGenerationFailed(String generationId, String message) {
        stateService.markFailed(generationId, message);
    }

    /**
     * 检查生成是否已取消
     */
    public boolean isGenerationCancelled(String generationId) {
        return Boolean.TRUE.equals(stopFlags.get(generationId));
    }

    /**
     * 停止响应
     */
    public void stopResponse(String userId, String generationId) {
        String targetGenerationId = generationId;
        if (targetGenerationId == null || targetGenerationId.isBlank()) {
            targetGenerationId = stateService.getActiveGenerationForUser(userId)
                    .map(ChatGenerationStateService.GenerationSnapshot::generationId)
                    .orElse(null);
        }

        if (targetGenerationId == null || targetGenerationId.isBlank()) {
            logger.warn("收到停止请求但未找到活动生成任务，用户ID: {}", userId);
            return;
        }

        logger.info("收到停止请求，用户ID: {}，generationId: {}", userId, targetGenerationId);

        stopFlags.put(targetGenerationId, true);
        CancellationContext.cancel(targetGenerationId);
        stateService.markCancelled(targetGenerationId);

        LlmProviderRouter.StreamHandle streamHandle = activeStreams.get(targetGenerationId);
        if (streamHandle != null) {
            streamHandle.cancel();
        }

        sessionRegistry.sendJsonToUser(userId, Map.of(
                "type", "stop",
                "generationId", targetGenerationId,
                "message", "响应已停止",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 清理生成状态
     */
    public void cleanupGenerationState(String generationId, Throwable throwable) {
        responseBuilders.remove(generationId);
        generationReferenceMappings.remove(generationId);
        generationTraces.remove(generationId);
        stopFlags.remove(generationId);
        activeStreams.remove(generationId);
        CancellationContext.clear(generationId);
    }

    /**
     * 获取引用详情
     */
    public ReferenceInfo getReferenceDetail(String generationId, int referenceNumber) {
        Map<Integer, ReferenceInfo> referenceMapping = generationReferenceMappings.get(generationId);
        if (referenceMapping == null) {
            referenceMapping = stateService.getGeneration(generationId)
                    .map(ChatGenerationStateService.GenerationSnapshot::referenceMappings)
                    .filter(mappings -> !mappings.isEmpty())
                    .map(this::toReferenceInfoMap)
                    .orElse(null);
        }

        if (referenceMapping == null) {
            logger.error("未找到生成任务 {} 的引用映射", generationId);
            return null;
        }

        return referenceMapping.get(referenceNumber);
    }

    /**
     * 更新引用映射
     */
    public void updateReferenceMappings(String generationId, Map<Integer, ReferenceInfo> mapping) {
        generationReferenceMappings.put(generationId, mapping);
        stateService.updateReferenceMappings(generationId, toSerializableReferenceMappings(mapping));
    }

    /**
     * 增量登记单条引用（编号由调用方分配）。
     * Wiki 概念页占用 101 起的号段，与 search_knowledge 工具文本里的 [1..N] 编号互不冲突。
     */
    public void registerReference(String generationId, int referenceNumber, ReferenceInfo info) {
        if (generationId == null || info == null) {
            return;
        }
        generationReferenceMappings
                .computeIfAbsent(generationId, key -> new ConcurrentHashMap<>())
                .put(referenceNumber, info);
    }

    public Map<Integer, ReferenceInfo> getReferenceMappings(String generationId) {
        Map<Integer, ReferenceInfo> mappings = generationReferenceMappings.get(generationId);
        return mappings == null ? Map.of() : new HashMap<>(mappings);
    }

    /**
     * 序列化引用映射
     */
    private Map<String, Map<String, Object>> toSerializableReferenceMappings(Map<Integer, ReferenceInfo> referenceMapping) {
        Map<String, Map<String, Object>> serialized = new HashMap<>();
        if (referenceMapping == null || referenceMapping.isEmpty()) {
            return serialized;
        }
        for (Map.Entry<Integer, ReferenceInfo> entry : referenceMapping.entrySet()) {
            ReferenceInfo detail = entry.getValue();
            Map<String, Object> item = new HashMap<>();
            item.put("fileMd5", detail.fileMd5());
            item.put("fileName", detail.fileName());
            item.put("pageNumber", detail.pageNumber());
            item.put("anchorText", detail.anchorText());
            item.put("retrievalMode", detail.retrievalMode());
            item.put("retrievalLabel", detail.retrievalLabel());
            item.put("retrievalQuery", detail.retrievalQuery());
            item.put("matchedChunkText", detail.matchedChunkText());
            item.put("evidenceSnippet", detail.evidenceSnippet());
            item.put("score", detail.score());
            item.put("chunkId", detail.chunkId());
            serialized.put(String.valueOf(entry.getKey()), item);
        }
        return serialized;
    }

    /**
     * 反序列化引用映射
     */
    private Map<Integer, ReferenceInfo> toReferenceInfoMap(Map<String, Map<String, Object>> serializedMappings) {
        Map<Integer, ReferenceInfo> referenceMap = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : serializedMappings.entrySet()) {
            Map<String, Object> item = entry.getValue();
            referenceMap.put(Integer.parseInt(entry.getKey()), new ReferenceInfo(
                    (String) item.get("fileMd5"),
                    (String) item.get("fileName"),
                    item.get("pageNumber") instanceof Number number ? number.intValue() : null,
                    (String) item.get("anchorText"),
                    (String) item.get("retrievalMode"),
                    (String) item.get("retrievalLabel"),
                    (String) item.get("retrievalQuery"),
                    (String) item.get("matchedChunkText"),
                    (String) item.get("evidenceSnippet"),
                    item.get("score") instanceof Number number ? number.doubleValue() : null,
                    item.get("chunkId") instanceof Number number ? number.intValue() : null
            ));
        }
        return referenceMap;
    }

    public void sendError(String userId, String generationId, String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("type", "error");
        errorResponse.put("generationId", generationId);
        errorResponse.put("error", message);
        sessionRegistry.sendJsonToUser(userId, errorResponse);
    }

    /**
     * 推送计划审阅请求（/plan 规划完成后等待前端决策）
     */
    public void sendPlanReview(String userId, String generationId, String conversationId,
                               String planId, String goal, List<Map<String, Object>> steps,
                               long deadlineEpochMillis) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "plan_review");
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("planId", planId);
        payload.put("goal", goal);
        payload.put("steps", steps);
        payload.put("deadline", deadlineEpochMillis);
        sessionRegistry.sendJsonToUser(userId, payload);
    }

    /**
     * 推送计划步骤状态变化
     */
    public void sendPlanStep(String userId, String generationId, String conversationId,
                             String planId, String stepId, String status,
                             String message, String result) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "plan_step");
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("planId", planId);
        payload.put("stepId", stepId);
        payload.put("status", status);
        payload.put("message", message);
        if (result != null) {
            payload.put("result", result);
        }
        sendTraced(userId, generationId, payload);
    }

    /**
     * 推送计划整体状态变化
     */
    public void sendPlanStatus(String userId, String generationId, String conversationId,
                               String planId, String state, double progress) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "plan_status");
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("planId", planId);
        payload.put("state", state);
        payload.put("progress", progress);
        sendTraced(userId, generationId, payload);
    }

    /**
     * 推送多 Agent 协作事件
     */
    public void sendTeamEvent(String userId, String generationId, String conversationId,
                              String phase, String role, String workerName,
                              String stepId, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "team_event");
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("phase", phase);
        payload.put("role", role);
        payload.put("workerName", workerName);
        payload.put("stepId", stepId);
        payload.put("message", message);
        sendTraced(userId, generationId, payload);
    }

    public void sendMemorySaved(String userId, String generationId, String conversationId, String fact) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "memory_saved");
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("fact", fact);
        sessionRegistry.sendJsonToUser(userId, payload);
    }

    public void sendSkillLoaded(String userId, String generationId, String conversationId, String name) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "skill_loaded");
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("name", name);
        sessionRegistry.sendJsonToUser(userId, payload);
    }

    /**
     * 推送 HITL 审批请求（前端弹窗决策后回传 approval_response）
     */
    public void sendApprovalRequest(String userId, String generationId, String conversationId,
                                    com.mindflow.harness.hitl.HitlMessages.ApprovalRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "approval_request");
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("approvalId", request.approvalId());
        payload.put("tool", request.tool());
        payload.put("toolCallId", request.toolCallId());
        payload.put("riskLevel", request.riskLevel());
        payload.put("riskReason", request.riskReason());
        payload.put("args", request.args());
        payload.put("timeoutSeconds", request.timeoutSeconds());
        if (request.serverName() != null) {
            payload.put("serverName", request.serverName());
        }
        sessionRegistry.sendJsonToUser(userId, payload);
    }


    /**
     * 引用信息记录
     */
    public record ReferenceInfo(
            String fileMd5,
            String fileName,
            Integer pageNumber,
            String anchorText,
            String retrievalMode,
            String retrievalLabel,
            String retrievalQuery,
            String matchedChunkText,
            String evidenceSnippet,
            Double score,
            Integer chunkId
    ) {}
}
