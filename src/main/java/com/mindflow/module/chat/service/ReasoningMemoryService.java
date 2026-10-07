package com.mindflow.module.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多跳推理记忆服务
 * 在ReAct循环中累积已发现的信息，结构化注入下一轮推理上下文
 */
@Service
public class ReasoningMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ReasoningMemoryService.class);

    private final Map<String, MemoryState> memories = new ConcurrentHashMap<>();

    /**
     * 初始化一轮推理的记忆
     */
    public void initialize(String generationId, String userMessage) {
        memories.put(generationId, new MemoryState(userMessage));
        logger.debug("初始化推理记忆: generationId={}", generationId);
    }

    /**
     * 记录工具调用结果，提取关键发现
     */
    public void recordToolResult(String generationId, int roundNumber,
                                 String toolName, Map<String, Object> toolArgs,
                                 String toolResult) {
        MemoryState mem = memories.get(generationId);
        if (mem == null) return;

        // 从工具结果中提取关键信息
        Finding finding = extractFinding(toolName, toolResult, roundNumber);
        if (finding != null) {
            mem.findings.add(finding);
            logger.debug("记录推理发现 [第{}轮] {}: {}", roundNumber, toolName, finding.summary());
        }

        // 记录原始步骤日志（用于调试）
        if (toolResult != null && !toolResult.isEmpty()) {
            String trSummary = toolResult.length() > 100
                    ? toolResult.substring(0, 100) + "..."
                    : toolResult;
            mem.stepLog.add(String.format("第%d轮 [%s]: %s", roundNumber, toolName, trSummary));
        }
    }

    /**
     * 记录LLM的推理过程文本
     */
    public void recordLlmReasoning(String generationId, int roundNumber, String reasoning) {
        MemoryState mem = memories.get(generationId);
        if (mem == null) return;

        if (reasoning != null && !reasoning.isBlank()) {
            String shortReasoning = reasoning.length() > 150
                    ? reasoning.substring(0, 150) + "..."
                    : reasoning;
            mem.stepLog.add(String.format("第%d轮 推理: %s", roundNumber, shortReasoning));
        }
    }

    /**
     * 构建推理上下文，注入给LLM
     */
    public String buildReasoningContext(String generationId) {
        MemoryState mem = memories.get(generationId);
        if (mem == null || mem.findings.isEmpty()) return "";

        StringBuilder ctx = new StringBuilder();
        ctx.append("【多跳推理进度】\n");
        ctx.append("用户原始问题：").append(mem.originalQuestion).append("\n\n");
        ctx.append("已确认的信息（按发现顺序）：\n");

        for (int i = 0; i < mem.findings.size(); i++) {
            Finding f = mem.findings.get(i);
            ctx.append(i + 1).append(". [第").append(f.roundNumber).append("轮] ");
            ctx.append(f.summary()).append("\n");
        }

        ctx.append("\n请基于以上已确认的信息继续推理。如果信息足以回答用户问题，直接给出最终答案，无需再调用工具。");

        return ctx.toString();
    }

    /**
     * 获取累计发现的数量（用于判断是否需要终止）
     */
    public int getFindingCount(String generationId) {
        MemoryState mem = memories.get(generationId);
        return mem == null ? 0 : mem.findings.size();
    }

    /**
     * 最新一条推理发现（用于向前端推送 memory_finding 事件）
     */
    public java.util.Optional<FindingView> latestFinding(String generationId) {
        MemoryState mem = memories.get(generationId);
        if (mem == null || mem.findings.isEmpty()) {
            return java.util.Optional.empty();
        }
        Finding finding = mem.findings.get(mem.findings.size() - 1);
        return java.util.Optional.of(new FindingView(
                finding.roundNumber(), finding.toolName(), finding.summary()));
    }

    /**
     * 清理推理记忆
     */
    public void cleanup(String generationId) {
        memories.remove(generationId);
        logger.debug("清理推理记忆: generationId={}", generationId);
    }

    /**
     * 从工具结果中提取关键发现
     */
    private Finding extractFinding(String toolName, String result, int roundNumber) {
        if (result == null || result.isBlank()) return null;

        // 工具名称直接作为发现标签
        String summary;
        if ("search_knowledge".equals(toolName)) {
            // 搜索工具：提取检索到的内容摘要
            String cleaned = result
                    .replaceAll("检索到 \\d+ 个知识库片段。请基于这些片段回答用户问题；不得声称知识库暂无相关信息。", "")
                    .replaceAll("如果片段信息不足，请说明.*", "")
                    .replaceAll("\\[\\d+\\] \\([^)]+\\)", "")
                    .trim();

            if (cleaned.length() > 200) {
                cleaned = cleaned.substring(0, 200) + "...";
            }
            summary = "知识库检索结果：" + (cleaned.isEmpty() ? result.substring(0, Math.min(150, result.length())) : cleaned);

        } else if ("generate_summary".equals(toolName)) {
            summary = result.length() > 200 ? result.substring(0, 200) + "..." : result;

        } else if ("submit_feedback".equals(toolName)) {
            summary = "用户反馈已记录：" + (result.length() > 100 ? result.substring(0, 100) + "..." : result);

        } else if ("knowledge_stats".equals(toolName)) {
            summary = "知识库统计信息：" + (result.length() > 100 ? result.substring(0, 100) + "..." : result);

        } else {
            summary = result.length() > 200 ? result.substring(0, 200) + "..." : result;
        }

        return new Finding(roundNumber, toolName, summary);
    }

    /**
     * 内存状态
     */
    private static class MemoryState {
        final String originalQuestion;
        final List<Finding> findings = new ArrayList<>();
        final List<String> stepLog = new ArrayList<>();

        MemoryState(String originalQuestion) {
            this.originalQuestion = originalQuestion;
        }
    }

    /**
     * 单条推理发现
     */
    private record Finding(int roundNumber, String toolName, String summary) {}

    public record FindingView(int roundNumber, String toolName, String summary) {}
}
