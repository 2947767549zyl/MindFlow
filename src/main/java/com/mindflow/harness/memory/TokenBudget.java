package com.mindflow.harness.memory;

import java.util.List;
import java.util.Map;

/**
 * Token 预算管理器（移植自 MindFlow memory.TokenBudget，适配 Map 消息形态）。
 *
 * 策略：设定总 token 预算（系统提示 + 工具定义 + 对话历史 + 回复预留），
 * 每次调用 LLM 前检查预算，超出预算时触发压缩或裁剪。
 */
public class TokenBudget {
    private final int contextWindow;
    private final int reservedForSystem;
    private final int reservedForTools;
    private final int reservedForResponse;

    private int totalInputTokens;
    private int totalOutputTokens;
    private int llmCallCount;

    public TokenBudget(int contextWindow) {
        this(contextWindow, 500, 800, 2000);
    }

    public TokenBudget(int contextWindow, int reservedForSystem, int reservedForTools, int reservedForResponse) {
        this.contextWindow = contextWindow;
        this.reservedForSystem = reservedForSystem;
        this.reservedForTools = reservedForTools;
        this.reservedForResponse = reservedForResponse;
    }

    public int getAvailableForConversation() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    public boolean isWithinBudget(List<Map<String, Object>> messages) {
        return estimateMessagesTokens(messages) <= getAvailableForConversation();
    }

    public void recordUsage(int inputTokens, int outputTokens) {
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
        llmCallCount++;
    }

    public String getUsageReport() {
        double avgInput = llmCallCount > 0 ? (double) totalInputTokens / llmCallCount : 0;
        return String.format(
                "Token 统计: 调用 %d 次 | 总输入: %d | 总输出: %d | 平均输入: %.0f | 预算: %d (可用: %d)",
                llmCallCount, totalInputTokens, totalOutputTokens, avgInput,
                contextWindow, getAvailableForConversation());
    }

    public int getContextWindow() { return contextWindow; }
    public int getTotalInputTokens() { return totalInputTokens; }
    public int getTotalOutputTokens() { return totalOutputTokens; }
    public int getLlmCallCount() { return llmCallCount; }

    /**
     * 估算消息列表的 token 总数（中文≈1 token/字，英文≈1 token/4 字符的经验近似）。
     */
    public static int estimateMessagesTokens(List<Map<String, Object>> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (Map<String, Object> message : messages) {
            total += estimateTokens(String.valueOf(message.getOrDefault("content", "")));
            Object toolCalls = message.get("tool_calls");
            if (toolCalls != null) {
                total += estimateTokens(String.valueOf(toolCalls));
            }
            Object reasoning = message.get("reasoning_content");
            if (reasoning != null) {
                total += estimateTokens(String.valueOf(reasoning));
            }
        }
        // 每条消息额外开销约 4 tokens（role、separator 等）
        total += messages.size() * 4;
        return total;
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjkCount = 0;
        int otherCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                cjkCount++;
            } else {
                otherCount++;
            }
        }
        return cjkCount + (otherCount + 3) / 4;
    }
}
