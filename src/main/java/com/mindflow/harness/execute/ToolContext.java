package com.mindflow.harness.execute;

/**
 * 一次工具执行的上下文（设计 §7.3：由引擎每轮构造，工具不再自行反推 userId）。
 * parallel 批处理中显式传递 + 调用方 finally 清理，不依赖 InheritableThreadLocal。
 */
public record ToolContext(
        String userId,
        String conversationId,
        String generationId
) {
}
