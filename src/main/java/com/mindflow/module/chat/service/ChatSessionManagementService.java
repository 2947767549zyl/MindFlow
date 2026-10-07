package com.mindflow.module.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
public class ChatSessionManagementService {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionManagementService.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final com.mindflow.module.chat.repository.MessageFeedbackRepository messageFeedbackRepository;

    /** Redis 会话历史滑动窗口：保留最近多少轮（一轮=用户+助手两条） */
    @Value("${mindflow.chat.history.max-turns:20}")
    private int maxHistoryTurns;

    /** 会话历史 TTL（小时）：长期不活跃的会话自动过期，避免 Redis 无界增长 */
    @Value("${mindflow.chat.history.ttl-hours:168}")
    private long historyTtlHours;

    private static final String HISTORY_KEY_PREFIX = "conversation:";

    public ChatSessionManagementService(RedisTemplate<String, String> redisTemplate,
                                        ObjectMapper objectMapper,
                                        com.mindflow.module.chat.repository.MessageFeedbackRepository messageFeedbackRepository) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.messageFeedbackRepository = messageFeedbackRepository;
    }

    /**
     * 获取或创建会话ID
     */
    public String getOrCreateConversationId(String userId) {
        String key = "user:" + userId + ":current_conversation";
        String conversationId = redisTemplate.opsForValue().get(key);

        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
            redisTemplate.opsForValue().set(key, conversationId, Duration.ofDays(7));
            logger.info("为用户 {} 创建新的会话ID: {}", userId, conversationId);
        } else {
            logger.info("获取到用户 {} 的现有会话ID: {}", userId, conversationId);
        }

        return conversationId;
    }

    /**
     * 获取对话历史
     */
    public List<Map<String, String>> getConversationHistory(String conversationId) {
        return normalizeHistory(readRecords(conversationId));
    }

    /**
     * 追加一轮问答到 Redis 会话历史。
     *
     * 这是"多轮记忆"唯一的写入点：缺失（或空实现）会让 ReAct / Plan / Team 三个模式
     * 拿到的历史恒为空，表现为"同一会话里第二次提问完全失忆"。
     * 滑动窗口只保留最近 maxHistoryTurns 轮，并设置 TTL，防止 Redis 无界增长。
     */
    public void updateConversationHistory(String conversationId, String userMessage,
                                          String response, Map<String, Map<String, Object>> referenceMapping) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        boolean hasUser = userMessage != null && !userMessage.isBlank();
        boolean hasAssistant = response != null && !response.isBlank();
        if (!hasUser && !hasAssistant) {
            return;
        }
        try {
            List<Map<String, Object>> records = new ArrayList<>(readRecords(conversationId));
            if (hasUser) {
                records.add(record("user", userMessage));
            }
            if (hasAssistant) {
                records.add(record("assistant", response));
            }
            int maxRecords = Math.max(2, Math.max(1, maxHistoryTurns) * 2);
            if (records.size() > maxRecords) {
                records = new ArrayList<>(records.subList(records.size() - maxRecords, records.size()));
            }
            redisTemplate.opsForValue().set(HISTORY_KEY_PREFIX + conversationId,
                    objectMapper.writeValueAsString(records),
                    Duration.ofHours(Math.max(1, historyTtlHours)));
        } catch (Exception e) {
            logger.warn("写入会话历史失败: conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    private List<Map<String, Object>> readRecords(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return new ArrayList<>();
        }
        String json = redisTemplate.opsForValue().get(HISTORY_KEY_PREFIX + conversationId);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> records = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            return records == null ? new ArrayList<>() : records;
        } catch (Exception e) {
            logger.warn("会话历史解析失败，按空历史处理: conversationId={}, error={}", conversationId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, Object> record(String role, String content) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("role", role);
        record.put("content", content);
        return record;
    }

    /**
     * 构建近期反馈指导
     */
    public String buildRecentFeedbackGuidance(String userId) {
        Long parsedUserId = parseUserId(userId);
        if (parsedUserId == null) {
            return "";
        }
        List<com.mindflow.module.chat.entity.MessageFeedback> recent;
        try {
            recent = messageFeedbackRepository.findTop8ByUserIdOrderByCreatedAtDesc(parsedUserId);
        } catch (Exception e) {
            logger.warn("读取用户反馈失败: userId={}, error={}", userId, e.getMessage());
            return "";
        }
        if (recent == null || recent.isEmpty()) {
            return "";
        }

        List<String> rejected = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        for (com.mindflow.module.chat.entity.MessageFeedback item : recent) {
            String excerpt = firstNonBlank(item.getAnswerExcerpt(), item.getReason());
            if (excerpt == null) {
                continue;
            }
            if (excerpt.length() > 200) {
                excerpt = excerpt.substring(0, 200) + "…";
            }
            if ("bad".equalsIgnoreCase(item.getRating())) {
                rejected.add(excerpt);
            } else {
                accepted.add(excerpt);
            }
        }
        if (rejected.isEmpty() && accepted.isEmpty()) {
            return "";
        }

        StringBuilder guidance = new StringBuilder("## 近期用户反馈（请据此调整回答方式）\n");
        if (!rejected.isEmpty()) {
            guidance.append("用户明确否定过以下回答，不要重复同样的表达或结论：\n");
            for (String item : rejected) {
                guidance.append("- ").append(item).append('\n');
            }
        }
        if (!accepted.isEmpty()) {
            guidance.append("用户认可过以下回答，可作为风格参考：\n");
            for (String item : accepted) {
                guidance.append("- ").append(item).append('\n');
            }
        }
        return guidance.toString().trim();
    }

    private static Long parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second != null && !second.isBlank() ? second.trim() : null;
    }

    private List<Map<String, String>> normalizeHistory(List<Map<String, Object>> records) {
        List<Map<String, String>> history = new ArrayList<>();
        for (Map<String, Object> message : records) {
            Map<String, String> normalized = new HashMap<>();
            normalized.put("role", String.valueOf(message.getOrDefault("role", "")));
            normalized.put("content", String.valueOf(message.getOrDefault("content", "")));
            history.add(normalized);
        }
        return history;
    }
}
