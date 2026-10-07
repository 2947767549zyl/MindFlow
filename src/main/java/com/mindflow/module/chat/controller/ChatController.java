package com.mindflow.module.chat.controller;

import com.mindflow.module.chat.entity.MessageFeedback;
import com.mindflow.module.chat.handler.ChatWebSocketHandler;
import com.mindflow.module.chat.repository.MessageFeedbackRepository;
import com.mindflow.module.chat.service.ChatGenerationStateService;
import com.mindflow.utils.JwtUtils;
import com.mindflow.utils.LogUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final JwtUtils jwtUtils;
    private final ChatGenerationStateService chatGenerationStateService;
    private final MessageFeedbackRepository messageFeedbackRepository;

    public ChatController(JwtUtils jwtUtils,
                          ChatGenerationStateService chatGenerationStateService,
                          MessageFeedbackRepository messageFeedbackRepository) {
        this.jwtUtils = jwtUtils;
        this.chatGenerationStateService = chatGenerationStateService;
        this.messageFeedbackRepository = messageFeedbackRepository;
    }

    @PostMapping("/feedback")
    public ResponseEntity<?> submitFeedback(@RequestHeader("Authorization") String token,
                                            @RequestBody Map<String, Object> body) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }
        try {
            String rating = body.get("rating") == null ? "" : String.valueOf(body.get("rating")).trim().toLowerCase();
            if (!"good".equals(rating) && !"bad".equals(rating)) {
                return ResponseEntity.badRequest().body(responseBody(400, "rating 只能是 good 或 bad", null));
            }

            MessageFeedback feedback = new MessageFeedback();
            feedback.setUserId(Long.parseLong(userId));
            feedback.setRating(rating);
            feedback.setReason(truncate(body.get("reason"), 500));
            feedback.setConversationId(truncate(body.get("conversationId"), 64));
            feedback.setGenerationId(truncate(body.get("generationId"), 64));
            feedback.setAnswerExcerpt(truncate(body.get("answerExcerpt"), 1000));
            messageFeedbackRepository.save(feedback);

            return ResponseEntity.ok(responseBody(200, "反馈已记录", null));
        } catch (Exception e) {
            LogUtils.logBusinessError("SUBMIT_FEEDBACK", "system", "记录用户反馈失败", e);
            return ResponseEntity.status(500).body(responseBody(500, "服务器内部错误：" + e.getMessage(), null));
        }
    }

    private static String truncate(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
    
    /**
     * 获取WebSocket停止指令Token
     */
    @GetMapping("/websocket-token")
    public ResponseEntity<?> getWebSocketToken(@RequestHeader("Authorization") String token) {
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
            }
            String jwtToken = token.replace("Bearer ", "");
            if (!jwtUtils.validateToken(jwtToken)) {
                return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
            }

            String cmdToken = ChatWebSocketHandler.getInternalCmdToken();
            
            // 检查token是否有效
            if (cmdToken == null || cmdToken.trim().isEmpty()) {
                return ResponseEntity.status(500).body(responseBody(500, "Token生成失败", null));
            }
            
            return ResponseEntity.ok(responseBody(200, "获取WebSocket停止指令Token成功", Map.of("cmdToken", cmdToken)));
            
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_WEBSOCKET_TOKEN", "system", "获取WebSocket Token失败", e);
            return ResponseEntity.status(500).body(responseBody(500, "服务器内部错误：" + e.getMessage(), null));
        }
    }

    @GetMapping("/generation/{generationId}")
    public ResponseEntity<?> getGeneration(
            @PathVariable String generationId,
            @RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        return ResponseEntity.ok(responseBody(
                200,
                "获取生成状态成功",
                chatGenerationStateService.getGenerationForUser(generationId, userId).orElse(null)
        ));
    }

    @GetMapping("/active-generation")
    public ResponseEntity<?> getActiveGeneration(@RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        return ResponseEntity.ok(responseBody(
                200,
                "获取当前活动生成状态成功",
                chatGenerationStateService.getActiveGenerationForUser(userId).orElse(null)
        ));
    }

    private String extractValidatedUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }

        String jwtToken = authorization.replace("Bearer ", "");
        if (!jwtUtils.validateToken(jwtToken)) {
            return null;
        }
        return jwtUtils.extractUserIdFromToken(jwtToken);
    }

    private Map<String, Object> responseBody(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", data);
        return response;
    }
}
