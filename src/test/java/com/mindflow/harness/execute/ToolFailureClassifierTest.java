package com.mindflow.harness.execute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolFailureClassifierTest {

    @Test
    void classifiesRateLimit() {
        assertEquals(ToolFailureClassifier.Category.RATE_LIMIT,
                ToolFailureClassifier.classify("LLM全网分钟Token预算已达上限"));
        assertEquals(ToolFailureClassifier.Category.RATE_LIMIT,
                ToolFailureClassifier.classify("HTTP 429 Too Many Requests"));
    }

    @Test
    void classifiesTimeout() {
        assertEquals(ToolFailureClassifier.Category.TIMEOUT,
                ToolFailureClassifier.classify("工具执行超时（90秒），已取消"));
        assertEquals(ToolFailureClassifier.Category.TIMEOUT,
                ToolFailureClassifier.classify("requests.exceptions.Timeout: timed out"));
    }

    @Test
    void classifiesDenied() {
        assertEquals(ToolFailureClassifier.Category.PERMISSION_DENIED,
                ToolFailureClassifier.classify("🚫 已被安全策略拦截: 参数 path 路径越界"));
        assertEquals(ToolFailureClassifier.Category.PERMISSION_DENIED,
                ToolFailureClassifier.classify("403 Forbidden"));
    }

    @Test
    void classifiesNotFound() {
        assertEquals(ToolFailureClassifier.Category.NOT_FOUND,
                ToolFailureClassifier.classify("文件不存在: C:\\tmp\\a.txt"));
        assertEquals(ToolFailureClassifier.Category.NOT_FOUND,
                ToolFailureClassifier.classify("404 Not Found"));
    }

    @Test
    void classifiesMissingArg() {
        assertEquals(ToolFailureClassifier.Category.MISSING_ARG,
                ToolFailureClassifier.classify("path 不能为空"));
        assertEquals(ToolFailureClassifier.Category.MISSING_ARG,
                ToolFailureClassifier.classify("invalid parameter: expression"));
    }

    @Test
    void classifiesEmptyResult() {
        assertEquals(ToolFailureClassifier.Category.EMPTY_RESULT,
                ToolFailureClassifier.classify("未匹配到内容: pattern=foo"));
    }

    @Test
    void fallsBackToUnknown() {
        assertEquals(ToolFailureClassifier.Category.UNKNOWN, ToolFailureClassifier.classify("something exploded"));
        assertEquals(ToolFailureClassifier.Category.UNKNOWN, ToolFailureClassifier.classify(null));
    }

    @Test
    void signatureUsesToolNameAndCategoryOnly() {
        assertEquals("mcp__demo__read_text_file:NOT_FOUND",
                ToolFailureClassifier.signature("mcp__demo__read_text_file",
                        ToolFailureClassifier.Category.NOT_FOUND));
        assertEquals("unknown_tool:UNKNOWN",
                ToolFailureClassifier.signature(null, ToolFailureClassifier.Category.UNKNOWN));
    }

    @Test
    void onlyEnvironmentFailuresAreSelfHealing() {
        org.junit.jupiter.api.Assertions.assertTrue(ToolFailureClassifier.Category.RATE_LIMIT.selfHealing());
        org.junit.jupiter.api.Assertions.assertTrue(ToolFailureClassifier.Category.TIMEOUT.selfHealing());
        org.junit.jupiter.api.Assertions.assertFalse(ToolFailureClassifier.Category.MISSING_ARG.selfHealing());
        org.junit.jupiter.api.Assertions.assertFalse(ToolFailureClassifier.Category.PERMISSION_DENIED.selfHealing());
    }
}
