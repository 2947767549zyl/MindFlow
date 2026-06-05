package com.mindflow.memory;

import com.mindflow.llm.LlmClient;
import com.mindflow.context.ContextProfile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Memory 管理器 - Memory 系统的门面类
 *
 * 统一管理短期记忆、长期记忆、上下文压缩和检索，
 * 为 Agent 提供简洁的记忆存取接口。
 */
public class MemoryManager {
    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;
    private TokenBudget tokenBudget;
    private ContextProfile contextProfile;

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, ContextProfile.from(llmClient), null);
    }

    /**
     * @param llmClient      LLM 客户端（用于压缩时的摘要生成）
     * @param shortTermBudget 短期记忆 token 预算
     * @param contextWindow  模型上下文窗口大小
     */
    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow) {
        this(llmClient, shortTermBudget, contextWindow, null);
    }

    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow, LongTermMemory longTermMemory) {
        this(llmClient, ContextProfile.custom(contextWindow, shortTermBudget), longTermMemory);
    }

    private MemoryManager(LlmClient llmClient, ContextProfile contextProfile, LongTermMemory longTermMemory) {
        this.contextProfile = contextProfile;
        // 短期记忆 — 当前对话上下文
        this.shortTermMemory = new ConversationMemory(contextProfile.shortTermMemoryBudget());
        // 长期记忆 — 跨会话持久化事实
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        // 压缩旧对话 → 摘要 + 提取事实
        this.compressor = new ContextCompressor(llmClient);
        // 统一检索接口（合并 STM + LTM）
        this.retriever = new MemoryRetriever(shortTermMemory, this.longTermMemory);
        // 控制短期记忆总 token 上限
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
    }

    public void setLlmClient(LlmClient llmClient) {
        this.compressor.setLlmClient(llmClient);
        applyContextProfile(ContextProfile.from(llmClient));
    }

    public void applyContextProfile(ContextProfile contextProfile) {
        this.contextProfile = contextProfile;
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.shortTermMemory.setMaxTokens(contextProfile.shortTermMemoryBudget());
    }

    /**
     * 添加用户消息到短期记忆
     */
    public void addUserMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "user-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "user"),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /**
     * 添加助手回复到短期记忆
     */
    public void addAssistantMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "assistant-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "assistant"),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    // 工具结果在记忆中的最大长度（完整结果已在任务消息历史里，记忆只需保留摘要）
    private static final int MAX_TOOL_RESULT_CHARS = 500;

    /**
     * 添加工具执行结果到短期记忆（截断过长结果，避免快速撑满预算）
     */
    //添加工具执行结果到短期记忆（截断过长结果，避免快速撑满预算）
    public void addToolResult(String toolName, String result) {
        String truncated = result.length() > MAX_TOOL_RESULT_CHARS
                ? result.substring(0, MAX_TOOL_RESULT_CHARS) + "...(已截断)"
                : result;
        String content = "[" + toolName + "] " + truncated;
        MemoryEntry entry = new MemoryEntry(
                "tool-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.TOOL_RESULT,
                Map.of("source", "tool", "toolName", toolName),
                MemoryEntry.estimateTokens(content)
        );
        //简要来说，就是将短期记忆中的linkedhashmap，将entry作为value进行存储，key为entry的id
        shortTermMemory.store(entry);
        //这里还会对
        compressIfNeeded();
    }


      //存储关键事实到长期记忆
    //这里应该是采用了一种设计模式，长期记忆和短期记忆分别实现接口提供的方法
    public void storeFact(String fact) {
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryEntry.MemoryType.FACT,
                Map.of("source", "fact"),
                MemoryEntry.estimateTokens(fact)
        );
        //对于长期记忆，采用的是concurrentHashMap来存储，会将事实性的内容存储到.long_term_memory.json文件中
        longTermMemory.store(entry);
    }

    /**
     * 检索与查询最相关的记忆,事实上这个方法并没有被使用
     */
    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        return retriever.retrieve(query, limit);
    }

    /**
     * 构建用于 LLM 的记忆上下文
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return retriever.buildContextForQuery(query, maxTokens);
    }

    /**
     * 记录 token 使用
     */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens, cachedInputTokens);
    }

    /**
     * 检查并触发压缩（由 Agent 在 LLM 调用前主动调用）
     *
     * @return 是否执行了压缩
     */

    //每当添加新的记忆条目时，检查是否需要压缩。如果压缩成功，则返回 true。
    //需要压缩的条件是：短期记忆的 token 占用率超过 ContextProfile 配置的阈值（默认 90%），这个阈值具体是
    public boolean compressIfNeeded() {
        // 压缩永远可触发，模式概念已删除。触发条件仅看占用率是否到达 ContextProfile 配置的阈值（默认 90%）。
        if (!tokenBudget.needsCompression(shortTermMemory, contextProfile.compressionTriggerRatio())) {
            return false;
        }
        int beforeTokens = shortTermMemory.getTokenCount();
        System.out.println("📦 上下文占用达到压缩阈值（" + (int) (contextProfile.compressionTriggerRatio() * 100)
                + "%），触发压缩...");
        String summary = compressor.compress(shortTermMemory);
        if (summary != null) {
            int afterTokens = shortTermMemory.getTokenCount();
            System.out.println("   压缩完成: " + beforeTokens + " → " + afterTokens + " tokens，摘要: "
                    + summary.substring(0, Math.min(100, summary.length())) + "...");
        }
        return summary != null;
    }

    /**
     * 清空短期记忆（保留长期记忆）
     */
    public void clearShortTerm() {
        shortTermMemory.clear();
    }

    /**
     * 清空长期记忆
     */
    public void clearLongTerm() {
        longTermMemory.clear();
    }

    /**
     * 获取记忆系统的整体状态
     */
    //这个方法在系统中，用于获取系统状态，返回一个字符串，包含上下文策略、短期记忆、长期记忆和Token预算等信息。
    public String getSystemStatus() {
        return "上下文策略: " + contextProfile.summary() + "\n" +
                shortTermMemory.getStatusSummary() + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    // Getter
    public ConversationMemory getShortTermMemory() { return shortTermMemory; }
    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
    public ContextProfile getContextProfile() { return contextProfile; }
}
