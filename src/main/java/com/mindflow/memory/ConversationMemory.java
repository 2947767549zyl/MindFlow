package com.mindflow.memory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 短期记忆 - 管理当前对话的上下文
 *
 * 职责：
 * 1. 维护对话历史（用户消息、助手回复、工具调用与结果）
 * 2. 当 token 超出预算时，自动压缩旧消息（滑动窗口 + 摘要）
 * 3. 提供关键词检索能力
 *
 * 设计说明：
 * - 使用 LinkedHashMap 保证插入顺序，实现 FIFO 淘汰策略
 * - 超出预算时自动淘汰最旧的条目，并将淘汰的条目存入 compressedSummaries 供后续压缩
 */
public class ConversationMemory implements Memory {
    // 存储记忆条目的映射，按插入顺序排列，保证淘汰时按 FIFO 顺序
    private final LinkedHashMap<String, MemoryEntry> entries;
    // 最大 token 预算，超过此值触发自动淘汰机制
    private int maxTokens;
    // 当前已使用的 token 总数
    private int currentTokens;
    // 已淘汰条目的压缩摘要列表，用于后续上下文压缩时使用
    private final List<MemoryEntry> compressedSummaries;

    /**
     * 构造函数，初始化短期记忆
     * @param maxTokens 最大 token 预算，超出时触发自动淘汰
     */
    public ConversationMemory(int maxTokens) {
        this.entries = new LinkedHashMap<>();  // 初始化有序的条目存储
        this.maxTokens = maxTokens;             // 设置最大 token 预算
        this.currentTokens = 0;                 // 初始 token 计数为 0
        this.compressedSummaries = new ArrayList<>();  // 初始化压缩摘要列表
    }

    /**
     * 存储新的记忆条目，并检查是否超出预算
     * @param entry 要存储的记忆条目
     */
    @Override
    public void store(MemoryEntry entry) {
        entries.put(entry.getId(), entry);      // 将新条目添加到映射中
        currentTokens += entry.getTokenCount();  // 累加 token 计数

        // 超出预算时自动淘汰最旧的条目（保留至少一条，避免空列表）
        while (currentTokens > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    /**
     * 根据 ID 检索记忆条目
     * @param id 记忆条目的唯一标识
     * @return Optional 包装的 MemoryEntry，若不存在则返回空 Optional
     */
    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    /**
     * 根据关键词搜索记忆条目
     * @param query 搜索关键词
     * @param limit 返回结果的最大数量
     * @return 匹配的记忆条目列表
     */
    @Override
    public List<MemoryEntry> search(String query, int limit) {
        // 将查询字符串分词为 token 集合
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
        // 过滤出内容包含所有查询 token 的条目，并限制返回数量
        return entries.values().stream()
                .filter(entry -> MemoryQueryTokenizer.matches(entry.getContent(), queryTokens))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有记忆条目
     * @return 包含所有 MemoryEntry 的列表
     */
    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    /**
     * 根据 ID 删除记忆条目
     * @param id 要删除的记忆条目 ID
     * @return 删除成功返回 true，否则返回 false
     */
    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);  // 从映射中移除条目
        if (removed != null) {
            currentTokens -= removed.getTokenCount();  // 减去对应的 token 计数
            return true;
        }
        return false;
    }

    /**
     * 清空所有记忆条目和压缩摘要
     */
    @Override
    public void clear() {
        entries.clear();                  // 清空所有条目
        currentTokens = 0;                 // 重置 token 计数
        compressedSummaries.clear();      // 清空压缩摘要列表
    }

    /**
     * 获取当前使用的 token 总数
     * @return 当前 token 计数
     */
    @Override
    public int getTokenCount() {
        return currentTokens;
    }

    /**
     * 获取当前存储的记忆条目数量
     * @return 条目数量
     */
    @Override
    public int size() {
        return entries.size();
    }

    /**
     * 获取最大 token 预算
     * @return 最大 token 数
     */
    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * 设置新的最大 token 预算，并触发必要的淘汰
     * @param maxTokens 新的最大 token 数，必须为正数
     * @throws IllegalArgumentException 若 maxTokens <= 0
     */
    public void setMaxTokens(int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        this.maxTokens = maxTokens;
        // 如果当前 token 超出新预算，触发淘汰直到满足预算
        while (currentTokens > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    /**
     * 淘汰最旧的一条记忆，并将其加入压缩摘要列表
     * 使用 LinkedHashMap 的顺序特性，按 FIFO 策略淘汰
     */
    private void evictOldest() {
        // 获取首个条目（最旧的）
        Iterator<Map.Entry<String, MemoryEntry>> it = entries.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<String, MemoryEntry> oldest = it.next();
            it.remove();  // 从映射中移除
            currentTokens -= oldest.getValue().getTokenCount();  // 减去 token 计数
            compressedSummaries.add(oldest.getValue());  // 加入压缩摘要列表
        }
    }

    /**
     * 获取已压缩淘汰的记忆摘要列表
     * @return 不可修改的压缩摘要列表
     */
    public List<MemoryEntry> getCompressedSummaries() {
        return Collections.unmodifiableList(compressedSummaries);
    }

    /**
     * 将压缩摘要回注到记忆中（上下文压缩后调用）
     * 用于将 LLM 生成的压缩摘要重新注入到记忆存储中
     * @param summary 压缩摘要条目
     */
    public void injectSummary(MemoryEntry summary) {
        // 清空旧的压缩摘要，因为已经合并到主存储中
        compressedSummaries.clear();
        // 将摘要作为新条目插入到映射中
        entries.put(summary.getId(), summary);
        currentTokens += summary.getTokenCount();
    }

    /**
     * 获取记忆使用率
     * @return 当前 token 使用比例（0.0 ~ 1.0）
     */
    public double getUsageRatio() {
        return maxTokens > 0 ? (double) currentTokens / maxTokens : 0;
    }

    /**
     * 生成记忆状态摘要，用于显示当前记忆状态
     * @return 格式化的状态字符串，包含条目数、token 数、预算、使用率和已压缩数量
     */
    public String getStatusSummary() {
        return String.format("短期记忆: %d条 / %d tokens (预算: %d, 使用率: %.0f%%, 已压缩: %d条)",
                entries.size(), currentTokens, maxTokens, getUsageRatio() * 100, compressedSummaries.size());
    }
}
