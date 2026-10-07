package com.mindflow.harness.execute;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Agent 循环的退出预算（移植自 MindFlow CLI 版，适配服务端配置驱动）。
 *
 * 设计目标：是否继续下一轮的主导权交给 LLM——只要它返回 content 不再调用工具，循环就退出。
 * 本类只承担三种"保险阀"职责：
 * 1. Token 预算：累计 input + output token 超过阈值后强制收尾（默认 Integer.MAX_VALUE 不生效）
 * 2. 停滞检测：连续 N 轮完全相同的工具名 + 参数，判定为死循环
 * 3. 硬轮数兜底：累计迭代轮数超过 hardMaxIterations
 */
public class AgentBudget {

    public enum ExitReason {
        WITHIN_BUDGET,
        TOKEN_BUDGET_EXCEEDED,
        STAGNATION_DETECTED,
        HARD_ITERATION_LIMIT
    }

    private final int tokenBudget;
    private final int stagnationWindow;
    private final int hardMaxIterations;

    private final Deque<String> recentToolSignatures = new ArrayDeque<>();
    private int iteration;
    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCachedInputTokens;
    private boolean stagnant;

    public AgentBudget(int tokenBudget, int stagnationWindow, int hardMaxIterations) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
        if (stagnationWindow < 2) {
            throw new IllegalArgumentException("stagnationWindow must be >= 2");
        }
        if (hardMaxIterations <= 0) {
            throw new IllegalArgumentException("hardMaxIterations must be positive");
        }
        this.tokenBudget = tokenBudget;
        this.stagnationWindow = stagnationWindow;
        this.hardMaxIterations = hardMaxIterations;
    }

    public int beginIteration() {
        return ++iteration;
    }

    public void recordTokens(int inputTokens, int outputTokens, int cachedInputTokens) {
        this.totalInputTokens += Math.max(0, inputTokens);
        this.totalOutputTokens += Math.max(0, outputTokens);
        this.totalCachedInputTokens += Math.max(0, cachedInputTokens);
    }

    public void recordToolCalls(List<ToolCallSignature> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            recentToolSignatures.clear();
            return;
        }
        StringBuilder signature = new StringBuilder();
        for (ToolCallSignature tc : toolCalls) {
            signature.append(tc.toolName()).append('|').append(tc.arguments()).append(';');
        }
        recentToolSignatures.addLast(signature.toString());
        while (recentToolSignatures.size() > stagnationWindow) {
            recentToolSignatures.removeFirst();
        }
        if (recentToolSignatures.size() == stagnationWindow) {
            String first = recentToolSignatures.peekFirst();
            stagnant = recentToolSignatures.stream().allMatch(sig -> sig.equals(first));
        }
    }

    public ExitReason check() {
        if (stagnant) {
            return ExitReason.STAGNATION_DETECTED;
        }
        if (totalInputTokens + totalOutputTokens >= tokenBudget) {
            return ExitReason.TOKEN_BUDGET_EXCEEDED;
        }
        if (iteration >= hardMaxIterations) {
            return ExitReason.HARD_ITERATION_LIMIT;
        }
        return ExitReason.WITHIN_BUDGET;
    }

    public int iteration() {
        return iteration;
    }

    public int totalInputTokens() {
        return totalInputTokens;
    }

    public int totalOutputTokens() {
        return totalOutputTokens;
    }

    public String describeExit(ExitReason reason) {
        return switch (reason) {
            case WITHIN_BUDGET -> "未触发兜底条件";
            case TOKEN_BUDGET_EXCEEDED -> String.format(Locale.ROOT,
                    "Token 预算已用尽（%d / %d），任务被强制收尾",
                    totalInputTokens + totalOutputTokens, tokenBudget);
            case STAGNATION_DETECTED -> String.format(Locale.ROOT,
                    "检测到连续 %d 轮重复的工具调用，疑似死循环，已强制收尾",
                    stagnationWindow);
            case HARD_ITERATION_LIMIT -> String.format(Locale.ROOT,
                    "达到硬轮数上限（%d），已强制收尾", hardMaxIterations);
        };
    }

    public record ToolCallSignature(String toolName, String arguments) {}
}
