package com.mindflow.module.chat.service;

import com.mindflow.module.chat.handler.ChatStreamingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class ChatPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(ChatPersistenceService.class);

    private final ConversationService conversationService;
    private final ChatSessionManagementService sessionService;

    public ChatPersistenceService(ConversationService conversationService,
                                  ChatSessionManagementService sessionService) {
        this.conversationService = conversationService;
        this.sessionService = sessionService;
    }

    /**
     * 持久化对话到MySQL
     */
    public boolean persistConversation(String userId, String userMessage,
                                       String completeResponse, String conversationId,
                                       Map<Integer, ChatStreamingService.ReferenceInfo> referenceMappings) {
        return persistConversation(userId, userMessage, completeResponse, conversationId, referenceMappings, null);
    }

    public boolean persistConversation(String userId, String userMessage,
                                       String completeResponse, String conversationId,
                                       Map<Integer, ChatStreamingService.ReferenceInfo> referenceMappings,
                                       String traceJson) {
        try {
            Long userIdLong = Long.parseLong(userId);

            // 转换引用映射类型：Map<Integer, ReferenceInfo> -> Map<String, Map<String, Object>>
            Map<String, Map<String, Object>> serializedMappings = serializeReferenceMappings(referenceMappings);

            conversationService.recordConversation(
                    userIdLong, userMessage, completeResponse, conversationId, serializedMappings, traceJson);
            return true;
        } catch (Exception e) {
            logger.error("持久化对话历史失败: userId={}, conversationId={}", userId, conversationId, e);
            return false;
        }
    }

    /**
     * 更新Redis会话历史
     */
    public void updateRedisHistory(String conversationId, String userMessage,
                                   String response, Map<Integer, ChatStreamingService.ReferenceInfo> referenceMapping) {
        // 转换类型后更新Redis
        Map<String, Map<String, Object>> serializedMappings = serializeReferenceMappings(referenceMapping);
        sessionService.updateConversationHistory(conversationId, userMessage, response, serializedMappings);
    }

    /**
     * 序列化引用映射
     * Map<Integer, ReferenceInfo> -> Map<String, Map<String, Object>>
     */
    private Map<String, Map<String, Object>> serializeReferenceMappings(Map<Integer, ChatStreamingService.ReferenceInfo> referenceMapping) {
        if (referenceMapping == null || referenceMapping.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Map<String, Object>> serialized = new HashMap<>();
        for (Map.Entry<Integer, ChatStreamingService.ReferenceInfo> entry : referenceMapping.entrySet()) {
            ChatStreamingService.ReferenceInfo detail = entry.getValue();
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
}
