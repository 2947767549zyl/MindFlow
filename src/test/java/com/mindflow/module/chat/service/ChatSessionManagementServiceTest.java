package com.mindflow.module.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatSessionManagementServiceTest {

    private static final String CONVERSATION_ID = "conv-1";

    private final AtomicReference<String> stored = new AtomicReference<>();
    private final AtomicReference<Duration> storedTtl = new AtomicReference<>();
    private ChatSessionManagementService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            storedTtl.set(invocation.getArgument(2));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        service = new ChatSessionManagementService(redisTemplate, new ObjectMapper(),
                mock(com.mindflow.module.chat.repository.MessageFeedbackRepository.class));
        ReflectionTestUtils.setField(service, "maxHistoryTurns", 3);
        ReflectionTestUtils.setField(service, "historyTtlHours", 48L);
    }

    private void turn(String question, String answer) {
        service.updateConversationHistory(CONVERSATION_ID, question, answer, Map.of());
    }

    @Test
    void writesBothRolesAndReadsThemBack() {
        turn("帮我读项目背景", "这份文档讲的是 MindFlow 的立项背景");

        List<Map<String, String>> history = service.getConversationHistory(CONVERSATION_ID);

        assertEquals(2, history.size());
        assertEquals("user", history.get(0).get("role"));
        assertEquals("帮我读项目背景", history.get(0).get("content"));
        assertEquals("assistant", history.get(1).get("role"));
    }

    @Test
    void keepsOnlyRecentTurnsWhenWindowExceeded() {
        for (int i = 1; i <= 5; i++) {
            turn("第" + i + "轮问题", "第" + i + "轮回答");
        }

        List<Map<String, String>> history = service.getConversationHistory(CONVERSATION_ID);

        assertEquals(6, history.size(), "窗口为 3 轮 => 最多保留 6 条");
        assertEquals("第3轮问题", history.get(0).get("content"));
        assertEquals("第5轮回答", history.get(5).get("content"));
    }

    @Test
    void appliesTtlOnEveryWrite() {
        turn("问题", "回答");

        assertTrue(storedTtl.get() != null && storedTtl.get().toHours() == 48L,
                "写入必须带 TTL，实际=" + storedTtl.get());
    }

    @Test
    void ignoresBlankTurnsAndBlankConversationId() {
        service.updateConversationHistory(CONVERSATION_ID, "  ", "  ", Map.of());
        service.updateConversationHistory("  ", "问题", "回答", Map.of());

        assertTrue(service.getConversationHistory(CONVERSATION_ID).isEmpty());
    }
}
