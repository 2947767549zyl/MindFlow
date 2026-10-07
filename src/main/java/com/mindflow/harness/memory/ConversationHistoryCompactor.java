package com.mindflow.harness.memory;

import com.mindflow.structure.ai.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 压缩 Agent 实际发给 LLM 的消息列表（移植自 MindFlow memory.ConversationHistoryCompactor，
 * LLM 出口改为 LlmProviderRouter，消息形态为 Map）。
 *
 * 算法：
 * 1. 估算 messages 当前 token，未达 trigger 直接返回 false
 * 2. 找出所有 user message 的索引；保留最近 retainRecentRounds 个 user 起算的尾部
 * 3. 把 system 之后、splitIdx 之前的全部消息喂给 LLM 摘要
 * 4. 重建：[system] + [user("[已压缩的历史对话摘要]\n" + summary)] +
 *         [assistant("好的，已了解上下文。请继续。")] + [尾部保留消息]
 *
 * 关键约束：分割点必然落在 user message 边界，避免切断 tool_call / tool_result 的成对协议。
 */
public class ConversationHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);

    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;
    private static final int SUMMARY_MAX_COMPLETION_TOKENS = 1000;

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已经完成的关键操作（哪些工具调用了什么、返回了什么核心结果）
            3. 已经达成的共识或结论
            4. 仍未解决的问题或待办

            不要复述每条原文，不要列举所有工具调用，不要保留无关闲聊。
            输出 1-3 段中文，不要用列表，不要加任何前缀或元描述。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;

    private final LlmProviderRouter llmProviderRouter;
    private final int retainRecentRounds;

    public ConversationHistoryCompactor(LlmProviderRouter llmProviderRouter) {
        this(llmProviderRouter, DEFAULT_RETAIN_RECENT_ROUNDS);
    }

    public ConversationHistoryCompactor(LlmProviderRouter llmProviderRouter, int retainRecentRounds) {
        this.llmProviderRouter = llmProviderRouter;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
    }

    public boolean compactIfNeeded(List<Map<String, Object>> history, int triggerTokens) {
        if (history == null || history.isEmpty()) return false;
        int currentTokens = TokenBudget.estimateMessagesTokens(history);
        if (currentTokens < triggerTokens) return false;

        int systemEnd = "system".equals(history.get(0).get("role")) ? 1 : 0;

        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).get("role"))) {
                userIndices.add(i);
            }
        }
        if (userIndices.size() <= retainRecentRounds) {
            log.info("compactIfNeeded skip: only {} user turns, < retain {}",
                    userIndices.size(), retainRecentRounds);
            return false;
        }

        int splitIdx = userIndices.get(userIndices.size() - retainRecentRounds);
        if (splitIdx <= systemEnd) return false;

        List<Map<String, Object>> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;

        String summary;
        try {
            summary = summarize(oldMsgs);
        } catch (Exception e) {
            log.warn("conversation summary LLM call failed; skip compaction", e);
            return false;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("conversation summary returned empty; skip compaction");
            return false;
        }

        List<Map<String, Object>> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(textMessage("user", "[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(textMessage("assistant", "好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        history.clear();
        history.addAll(rebuilt);
        log.info(String.format(Locale.ROOT,
                "compacted conversation history: tokens %d -> %d, messages -> %d, summary chars %d",
                currentTokens, afterTokens, rebuilt.size(), summary.length()));
        return true;
    }

    /**
     * 真正调 LLM 摘要。protected 以便测试通过子类替换。
     */
    protected String summarize(List<Map<String, Object>> messages) {
        if (llmProviderRouter == null) {
            throw new IllegalStateException("LLM router not configured");
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> message : messages) {
            sb.append(String.valueOf(message.get("role")).toUpperCase(Locale.ROOT)).append(": ");
            Object content = message.get("content");
            if (content != null) {
                sb.append(content);
            }
            Object toolCalls = message.get("tool_calls");
            if (toolCalls != null) {
                sb.append("\n  TOOL_CALLS ").append(toolCalls);
            }
            sb.append("\n\n");
            if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                sb.append("...(超长内容已截断)\n");
                break;
            }
        }
        String prompt = String.format(SUMMARY_PROMPT, sb.toString());
        List<Map<String, Object>> request = List.of(
                textMessage("system", "你是一个对话摘要助手，只输出摘要本身，不输出元描述。"),
                textMessage("user", prompt)
        );
        var turn = llmProviderRouter.completeReActTurn(
                "system-compactor", request, null, SUMMARY_MAX_COMPLETION_TOKENS);
        return turn.content();
    }

    public int retainRecentRounds() {
        return retainRecentRounds;
    }

    private static Map<String, Object> textMessage(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
