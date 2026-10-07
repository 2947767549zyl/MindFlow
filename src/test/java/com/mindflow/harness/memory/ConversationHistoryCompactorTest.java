package com.mindflow.harness.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationHistoryCompactorTest {

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static final class StubCompactor extends ConversationHistoryCompactor {
        private StubCompactor() {
            super(null);
        }

        @Override
        protected String summarize(List<Map<String, Object>> messages) {
            return "这是压缩后的历史摘要";
        }
    }

    @Test
    void compactsWhenOverTriggerAndKeepsRecentRounds() {
        ConversationHistoryCompactor compactor = new StubCompactor();
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(msg("system", "系统提示"));
        for (int i = 1; i <= 5; i++) {
            history.add(msg("user", "用户第" + i + "轮提问"));
            history.add(msg("assistant", "助手第" + i + "轮回答"));
        }

        boolean compacted = compactor.compactIfNeeded(history, 1);

        assertTrue(compacted);
        assertEquals("system", history.get(0).get("role"));
        assertEquals("user", history.get(1).get("role"));
        assertTrue(String.valueOf(history.get(1).get("content")).contains("已压缩的历史对话摘要"));
        assertTrue(String.valueOf(history.get(1).get("content")).contains("这是压缩后的历史摘要"));
        assertEquals("assistant", history.get(2).get("role"));

        String tail = history.stream().skip(3)
                .map(m -> String.valueOf(m.get("content")))
                .reduce("", (a, b) -> a + "|" + b);
        assertTrue(tail.contains("用户第3轮提问"));
        assertTrue(tail.contains("用户第5轮提问"));
        assertFalse(tail.contains("用户第1轮提问"));
    }

    @Test
    void skipsWhenBelowTrigger() {
        ConversationHistoryCompactor compactor = new StubCompactor();
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(msg("system", "系统提示"));
        history.add(msg("user", "短对话"));

        assertFalse(compactor.compactIfNeeded(history, 100000));
        assertEquals(2, history.size());
    }

    @Test
    void skipsWhenNotEnoughUserTurnsToSplit() {
        ConversationHistoryCompactor compactor = new StubCompactor();
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(msg("system", "系统提示"));
        for (int i = 1; i <= 3; i++) {
            history.add(msg("user", "用户" + i));
            history.add(msg("assistant", "助手" + i));
        }

        assertFalse(compactor.compactIfNeeded(history, 1));
        assertEquals(7, history.size());
    }
}
