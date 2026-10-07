package com.mindflow.module.chat.service;

import com.mindflow.common.exception.RateLimitExceededException;
import com.mindflow.harness.execute.MultiAgentService;
import com.mindflow.harness.execute.PlanExecuteService;
import com.mindflow.module.admin.service.RateLimitService;
import com.mindflow.module.chat.handler.ChatStreamingService;
import com.mindflow.module.search.service.QueryRewriteService;
import com.mindflow.runtime.CancellationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

@Service
public class ChatOrchestrationService {

    private static final Logger logger = LoggerFactory.getLogger(ChatOrchestrationService.class);

    private final RateLimitService rateLimitService;
    private final ChatSessionManagementService sessionService;
    private final ReActAgentService reActAgentService;
    private final PlanExecuteService planExecuteService;
    private final MultiAgentService multiAgentService;
    private final ConversationService conversationService;
    private final ChatStreamingService streamingService;
    private final ChatPersistenceService persistenceService;
    private final ThreadPoolTaskExecutor chatMonitorExecutor;
    private final QueryRewriteService queryRewriteService;

    public ChatOrchestrationService(
            RateLimitService rateLimitService,
            ChatSessionManagementService sessionService,
            ReActAgentService reActAgentService,
            PlanExecuteService planExecuteService,
                                     MultiAgentService multiAgentService,
                                     ConversationService conversationService,
            ChatStreamingService streamingService,
            ChatPersistenceService persistenceService,
            @Qualifier("chatMonitorExecutor") ThreadPoolTaskExecutor chatMonitorExecutor,
            QueryRewriteService queryRewriteService) {
        this.rateLimitService = rateLimitService;
        this.sessionService = sessionService;
        this.reActAgentService = reActAgentService;
        this.planExecuteService = planExecuteService;
        this.multiAgentService = multiAgentService;
        this.conversationService = conversationService;
        this.streamingService = streamingService;
        this.persistenceService = persistenceService;
        this.chatMonitorExecutor = chatMonitorExecutor;
        this.queryRewriteService = queryRewriteService;
    }

    /**
     * 处理用户聊天消息（主入口）
     */
    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        logger.info("开始处理消息，用户ID: {}, 会话ID: {}", userId, session.getId());

        String conversationId = null;
        String generationId = null;

        try {
            // 1. 限流检查
            rateLimitService.checkChatByUser(userId);

            // /cancel 文本指令：不创建会话与生成任务，直接取消该用户当前生成（与停止按钮同语义）
            String route = detectRoute(userMessage);
            if ("CANCEL".equals(route)) {
                logger.info("收到 /cancel 指令，取消当前生成: userId={}", userId);
                streamingService.stopResponse(userId, null);
                return;
            }

            // 2. 获取或创建会话
            conversationId = sessionService.getOrCreateConversationId(userId);
            conversationService.ensureConversationSession(Long.parseLong(userId), conversationId, userMessage);

            // 3. 创建生成任务
            generationId = streamingService.createGeneration(userId, conversationId, userMessage);

            // 4. 发送开始信号
            streamingService.sendGenerationStart(userId, generationId, conversationId);

            final String finalConversationId = conversationId;
            final String finalGenerationId = generationId;
            CancellationContext.startRun(finalGenerationId);

            if ("PLAN".equals(route) || "TEAM".equals(route)) {
                String goal = stripCommandPrefix(userMessage);
                if (goal.isBlank()) {
                    streamingService.sendError(userId, generationId,
                            "请在命令后提供任务描述，例如 /plan 总结知识库中关于向量检索的内容");
                    streamingService.cleanupGenerationState(generationId, null);
                    return;
                }
                try {
                    chatMonitorExecutor.execute(() ->
                            runAgentMode(route, userId, goal, finalConversationId, finalGenerationId));
                } catch (Exception ex) {
                    handleThreadPoolRejected(userId, finalGenerationId, finalConversationId, ex);
                }
                return;
            }

            // ReAct 路径：仅该路径执行查询改写
            var history = sessionService.getConversationHistory(conversationId);
            var rewriteResult = queryRewriteService.rewrite(userId, userMessage, history);

            // 如果检测到敏感内容，拦截并返回错误
            if (rewriteResult.isSensitive()) {
                logger.warn("检测到敏感内容，拦截请求: userId={}", userId);
                streamingService.sendError(userId, null, "您的输入包含敏感内容，已被系统拦截");
                streamingService.cleanupGenerationState(generationId, null);
                return;
            }

            // 根据意图决定实际使用的消息
            String effectiveMessage = "RAG".equals(rewriteResult.intent())
                    ? rewriteResult.rewrittenQuery()
                    : userMessage;

            try {
                chatMonitorExecutor.execute(() ->
                        reActAgentService.executeReActLoopSafely(
                                userId, effectiveMessage, finalConversationId, finalGenerationId, history));
            } catch (Exception ex) {
                handleThreadPoolRejected(userId, finalGenerationId, finalConversationId, ex);
            }

        } catch (RateLimitExceededException e) {
            streamingService.sendRateLimitMessage(userId, generationId, e);
        } catch (Exception e) {
            logger.error("处理消息错误", e);
            if (generationId != null) {
                streamingService.markGenerationFailed(generationId, e.getMessage());
            }
            streamingService.handleError(userId, generationId, e);
        }
    }

    private void runAgentMode(String route, String userId, String goal,
                              String conversationId, String generationId) {
        try {
            List<Map<String, String>> history = sessionService.getConversationHistory(conversationId);
            String result = "PLAN".equals(route)
                    ? planExecuteService.run(userId, generationId, conversationId, goal, history)
                    : multiAgentService.run(userId, generationId, conversationId, goal, history);

            if (result != null && !result.isBlank() && !streamingService.isGenerationCancelled(generationId)) {
                streamingService.appendStreamChunk(userId, generationId, conversationId, result);
            }
            streamingService.sendCompletionNotification(userId, generationId, conversationId, false, false);
        } catch (Exception e) {
            logger.error("Agent 模式执行失败: route={}, generationId={}", route, generationId, e);
            streamingService.markGenerationFailed(generationId, e.getMessage());
            streamingService.handleError(userId, generationId, e);
            streamingService.sendCompletionNotification(userId, generationId, conversationId, true, false);
        } finally {
            streamingService.cleanupGenerationState(generationId, null);
        }
    }

    private String detectRoute(String userMessage) {
        if (userMessage == null) {
            return "REACT";
        }
        String trimmed = userMessage.trim();
        if (trimmed.equals("/cancel") || trimmed.startsWith("/cancel ")) {
            return "CANCEL";
        }
        if (trimmed.equals("/plan") || trimmed.startsWith("/plan ")) {
            return "PLAN";
        }
        if (trimmed.equals("/team") || trimmed.startsWith("/team ")) {
            return "TEAM";
        }
        return "REACT";
    }

    private String stripCommandPrefix(String userMessage) {
        if (userMessage == null) {
            return "";
        }
        String trimmed = userMessage.trim();
        int spaceIndex = trimmed.indexOf(' ');
        if (spaceIndex < 0) {
            return "";
        }
        return trimmed.substring(spaceIndex + 1).trim();
    }

    private void handleThreadPoolRejected(String userId, String generationId,
                                          String conversationId, Exception ex) {
        logger.warn("聊天处理线程池已满，generationId: {}", generationId);
        RuntimeException busyException = new RuntimeException("系统繁忙，请稍后重试");
        streamingService.markGenerationFailed(generationId, busyException.getMessage());
        streamingService.handleError(userId, generationId, busyException);
        streamingService.sendCompletionNotification(userId, generationId, conversationId, true, false);
        streamingService.cleanupGenerationState(generationId, ex);
    }
    /**
     * 停止响应
     */
    public void stopResponse(String userId, String generationId) {
        streamingService.stopResponse(userId, generationId);
    }

}
