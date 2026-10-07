package com.mindflow.module.chat.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.harness.execute.FailureDecisionService;
import com.mindflow.harness.execute.PlanReviewService;
import com.mindflow.harness.execute.TaskClarificationService;
import com.mindflow.harness.hitl.HitlApprovalRegistry;
import com.mindflow.harness.hitl.HitlMessages;
import com.mindflow.module.chat.service.ChatOrchestrationService;
import com.mindflow.utils.JwtUtils;
import java.util.Map;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String HEARTBEAT_PING = "__chat_ping__";
    private static final String HEARTBEAT_PONG = "__chat_pong__";
    private final ChatOrchestrationService chatHandler;
    private final ChatSessionRegistry chatSessionRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtUtils jwtUtils;
    private final PlanReviewService planReviewService;
    private final FailureDecisionService failureDecisionService;
    private final TaskClarificationService taskClarificationService;
    private final HitlApprovalRegistry hitlApprovalRegistry;

    // 内部指令令牌 - 可以从配置文件读取
    private static final String INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    public ChatWebSocketHandler(ChatOrchestrationService chatHandler, JwtUtils jwtUtils,
                                ChatSessionRegistry chatSessionRegistry,
                                PlanReviewService planReviewService,
                                FailureDecisionService failureDecisionService,
                                TaskClarificationService taskClarificationService,
                                HitlApprovalRegistry hitlApprovalRegistry) {
        this.chatHandler = chatHandler;
        this.jwtUtils = jwtUtils;
        this.chatSessionRegistry = chatSessionRegistry;
        this.planReviewService = planReviewService;
        this.failureDecisionService = failureDecisionService;
        this.taskClarificationService = taskClarificationService;
        this.hitlApprovalRegistry = hitlApprovalRegistry;
    }

    private static java.util.Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof java.util.Map<?, ?> map) || map.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null) {
                result.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return result;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String jwtToken;
        try {
            jwtToken = extractToken(session);
            if (!jwtUtils.validateToken(jwtToken)) {
                logger.debug("拒绝无效WebSocket连接，会话ID: {}", session.getId());
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
        } catch (Exception exception) {
            logger.warn("拒绝非法WebSocket连接，会话ID: {}, 原因: {}", session.getId(), exception.getMessage());
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception closeException) {
                logger.error("关闭无效WebSocket连接失败: {}", closeException.getMessage(), closeException);
            }
            return;
        }

        String userId = extractUserId(jwtToken);
        chatSessionRegistry.registerSession(userId, session);
        logger.info("WebSocket连接已建立，用户ID: {}，会话ID: {}，URI路径: {}",
                userId, session.getId(), session.getUri().getPath());

        // 发送会话ID到前端
        try {
            Map<String, String> connectionMessage = Map.of(
                    "type", "connection",
                    "sessionId", session.getId(),
                    "message", "WebSocket连接已建立"
            );
            String jsonMessage = objectMapper.writeValueAsString(connectionMessage);
            session.sendMessage(new TextMessage(jsonMessage));
            logger.info("已发送会话ID到前端: sessionId={}", session.getId());
        } catch (Exception e) {
            logger.error("发送会话ID失败: {}", e.getMessage(), e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = extractUserId(extractToken(session));
        try {
            String payload = message.getPayload();

            // 心跳消息只用于保活连接，不进入聊天处理链路。
            if (HEARTBEAT_PING.equals(payload)) {
                session.sendMessage(new TextMessage(HEARTBEAT_PONG));
                return;
            }

            logger.info("接收到消息，用户ID: {}，会话ID: {}，消息长度: {}",
                    userId, session.getId(), payload.length());

            // 检查是否是JSON格式的系统指令
            if (payload.trim().startsWith("{")) {
                try {
                    Map<String, Object> jsonMessage = objectMapper.readValue(payload, Map.class);
                    String messageType = (String) jsonMessage.get("type");
                    String internalToken = (String) jsonMessage.get("_internal_cmd_token");
                    String generationId = (String) jsonMessage.get("generationId");

                    // 只有包含正确内部令牌的停止指令才处理
                    if ("stop".equals(messageType) && INTERNAL_CMD_TOKEN.equals(internalToken)) {
                        // 处理停止指令
                        logger.info("收到有效的停止按钮指令，用户ID: {}，会话ID: {}", userId, session.getId());
                        chatHandler.stopResponse(userId, generationId);
                        return;
                    }

                    if ("plan_review_response".equals(messageType)) {
                        String planId = (String) jsonMessage.get("planId");
                        String action = (String) jsonMessage.get("action");
                        String feedback = (String) jsonMessage.get("feedback");
                        planReviewService.complete(planId, action, feedback, stringMap(jsonMessage.get("selections")));
                        return;
                    }

                    if ("task_clarification_response".equals(messageType)) {
                        String clarificationId = (String) jsonMessage.get("clarificationId");
                        String choice = (String) jsonMessage.get("choice");
                        String note = (String) jsonMessage.get("note");
                        taskClarificationService.complete(clarificationId, choice, note);
                        return;
                    }

                    if ("failure_decision_response".equals(messageType)) {
                        String decisionId = (String) jsonMessage.get("decisionId");
                        String action = (String) jsonMessage.get("action");
                        failureDecisionService.complete(decisionId, action);
                        return;
                    }

                    if ("approval_response".equals(messageType)) {
                        String approvalId = (String) jsonMessage.get("approvalId");
                        String decisionRaw = (String) jsonMessage.get("decision");
                        String reason = (String) jsonMessage.get("reason");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> modifiedArgs = (Map<String, Object>) jsonMessage.get("modifiedArgs");
                        HitlMessages.Decision decision = HitlMessages.Decision.parse(decisionRaw);
                        if (decision == null) {
                            logger.warn("收到无法识别的审批决策: decision={}", decisionRaw);
                            return;
                        }
                        hitlApprovalRegistry.complete(approvalId,
                                new HitlMessages.ApprovalResponse(approvalId, decision, modifiedArgs, reason));
                        return;
                    }

                    // 其他JSON消息当作普通消息处理
                    logger.debug("收到JSON格式的聊天消息，当作普通消息处理");
                } catch (Exception jsonParseError) {
                    // JSON解析失败，当作普通文本消息处理
                    logger.debug("JSON解析失败，当作普通消息处理: {}", jsonParseError.getMessage());
                }
            }

            // 普通聊天消息处理（保持向下兼容）
            chatHandler.processMessage(userId, payload, session);

        } catch (Exception e) {
            logger.error("处理消息出错，用户ID: {}，会话ID: {}，错误: {}",
                    userId, session.getId(), e.getMessage(), e);
            sendErrorMessage(session, "消息处理失败：" + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = "unknown";
        try {
            userId = extractUserId(extractToken(session));
            chatSessionRegistry.unregisterSession(userId, session);
        } catch (Exception e) {
            logger.debug("关闭连接时无法解析用户信息，会话ID: {}", session.getId());
        }

        if (CloseStatus.POLICY_VIOLATION.equals(status)) {
            logger.debug("WebSocket连接因策略校验失败被关闭，用户ID: {}，会话ID: {}，状态: {}",
                    userId, session.getId(), status);
        } else {
            logger.info("WebSocket连接已关闭，用户ID: {}，会话ID: {}，状态: {}",
                    userId, session.getId(), status);
        }

    }

    private String extractUserId(String jwtToken) {
        String userId = jwtUtils.extractUserIdFromToken(jwtToken);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("无法从JWT令牌中提取用户ID");
        }

        logger.debug("从JWT令牌中提取的用户ID: {}", userId);
        return userId;
    }

    private String extractToken(WebSocketSession session) {
        if (session.getUri() == null || session.getUri().getPath() == null) {
            throw new IllegalArgumentException("WebSocket URI is missing");
        }
        String path = session.getUri().getPath();
        String[] segments = path.split("/");
        return segments[segments.length - 1];
    }

    private void sendErrorMessage(WebSocketSession session, String errorMessage) {
        try {
            Map<String, String> error = Map.of("error", errorMessage);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
            logger.info("已发送错误消息到会话: {}, 错误: {}", session.getId(), errorMessage);
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取内部指令令牌 - 供前端调用
     */
    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }
}
