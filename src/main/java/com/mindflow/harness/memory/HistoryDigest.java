package com.mindflow.harness.memory;

import java.util.List;
import java.util.Map;

/**
 * 会话历史 → 有界上下文片段，供 Plan / Team 等非 ReAct 路径注入"上一轮聊了什么"。
 *
 * 为什么必须有上限：这段文本会进入每一次规划与任务执行的 LLM 调用，
 * 不设上限会成倍放大 token 消耗，并直接冲击 LLM 分钟预算（此前已因此触发过限流）。
 * 因此只保留最近若干轮、单条与总量都截断。
 */
public final class HistoryDigest {

    private static final int MAX_MESSAGES = 8;
    private static final int MAX_TOTAL_CHARS = 4000;
    private static final int MAX_MESSAGE_CHARS = 800;

    private HistoryDigest() {
    }

    public static String render(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        int from = Math.max(0, history.size() - MAX_MESSAGES);
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            Map<String, String> message = history.get(i);
            if (message == null) {
                continue;
            }
            String content = message.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            String role = "assistant".equalsIgnoreCase(message.get("role")) ? "助手" : "用户";
            String trimmed = content.trim();
            if (trimmed.length() > MAX_MESSAGE_CHARS) {
                trimmed = trimmed.substring(0, MAX_MESSAGE_CHARS) + "…（截断）";
            }
            builder.append(role).append(": ").append(trimmed).append('\n');
        }

        String text = builder.toString().trim();
        if (text.isEmpty()) {
            return "";
        }
        if (text.length() > MAX_TOTAL_CHARS) {
            text = text.substring(text.length() - MAX_TOTAL_CHARS);
            int newline = text.indexOf('\n');
            if (newline > 0) {
                text = text.substring(newline + 1);
            }
        }
        return "## 会话历史（此前已经发生过的对话，回答与规划时不要遗忘）\n" + text;
    }
}
