package com.mindflow.harness.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryDigestTest {

    private static Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    @Test
    void returnsEmptyForNoHistory() {
        assertEquals("", HistoryDigest.render(null));
        assertEquals("", HistoryDigest.render(List.of()));
    }

    @Test
    void rendersRolesAndHeader() {
        String digest = HistoryDigest.render(List.of(
                message("user", "帮我读一下项目背景文档"),
                message("assistant", "这份文档讲的是 MindFlow 的立项背景")));

        assertTrue(digest.contains("会话历史"), digest);
        assertTrue(digest.contains("用户: 帮我读一下项目背景文档"), digest);
        assertTrue(digest.contains("助手: 这份文档讲的是 MindFlow 的立项背景"), digest);
    }

    @Test
    void keepsOnlyRecentMessages() {
        List<Map<String, String>> history = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            history.add(message("user", "第" + i + "轮"));
        }

        String digest = HistoryDigest.render(history);

        assertFalse(digest.contains("第1轮"), digest);
        assertTrue(digest.contains("第12轮"), digest);
        assertTrue(digest.contains("第5轮"), digest);
    }

    @Test
    void truncatesLongSingleMessage() {
        String longContent = "x".repeat(3000);

        String digest = HistoryDigest.render(List.of(message("user", longContent)));

        assertTrue(digest.contains("（截断）"), digest);
        assertTrue(digest.length() < 2000, "单条消息必须被截断，实际长度=" + digest.length());
    }

    @Test
    void capsTotalLength() {
        List<Map<String, String>> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            history.add(message("user", "内容" + i + "：" + "y".repeat(900)));
        }

        String digest = HistoryDigest.render(history);

        assertTrue(digest.length() <= 4200, "总量必须有上限，实际长度=" + digest.length());
        assertTrue(digest.contains("内容7"), "应保留最近的对话");
    }
}
