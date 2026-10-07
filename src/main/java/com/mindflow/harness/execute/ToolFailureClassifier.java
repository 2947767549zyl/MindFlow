package com.mindflow.harness.execute;

import java.util.List;
import java.util.Locale;

/**
 * 工具失败归因分类器：把工具返回的错误正文归类为"可恢复类型"，并给出对应恢复策略。
 *
 * 签名刻意只用「工具名 + 可恢复类别」，不含具体参数值（文件名 / query）——
 * 否则签名永不重复、经验库永远命中不了，等于白建。
 */
public final class ToolFailureClassifier {

    public enum Category {
        MISSING_ARG("参数非法", "先按工具 schema 校正必填参数名与类型，不要猜参数名或类型"),
        NOT_FOUND("目标不存在", "先用 glob / list 类工具确认路径或名称真实存在，再调用该工具"),
        RATE_LIMIT("限流或预算受限", "这属于环境性失败且会自愈：等待窗口恢复后重试，不要换工具绕开"),
        TIMEOUT("执行超时", "缩小输入规模（更小范围 / 更少条数）后重试，必要时显式加超时参数"),
        EMPTY_RESULT("空结果", "换更宽的关键词或换工具；不要用同一条件重复调用"),
        PERMISSION_DENIED("被权限拦截", "该操作被安全策略或人工拒绝，原样重试无效，应改方案或向用户说明"),
        UNKNOWN("未知错误", "先读错误正文再决定下一步，避免原样重试");

        private final String label;
        private final String recoveryHint;

        Category(String label, String recoveryHint) {
            this.label = label;
            this.recoveryHint = recoveryHint;
        }

        public String label() {
            return label;
        }

        public String recoveryHint() {
            return recoveryHint;
        }

        public boolean selfHealing() {
            return this == RATE_LIMIT || this == TIMEOUT;
        }
    }

    private static final List<String> RATE_LIMIT_MARKERS =
            List.of("限流", "预算", "额度", "余额不足", "rate limit", "429", "too many requests");
    private static final List<String> TIMEOUT_MARKERS =
            List.of("超时", "timeout", "timed out");
    private static final List<String> DENIED_MARKERS =
            List.of("拒绝", "策略拦截", "路径越界", "黑名单", "无权限", "permission", "denied", "forbidden", "403");
    private static final List<String> NOT_FOUND_MARKERS =
            List.of("不存在", "未找到", "找不到", "not found", "no such", "404");
    private static final List<String> MISSING_ARG_MARKERS =
            List.of("不能为空", "必须", "参数", "非法", "无效", "invalid", "required", "missing", "schema");
    private static final List<String> EMPTY_MARKERS =
            List.of("未匹配", "为空", "没有找到", "空结果", "no result", "empty", "no matches");

    private ToolFailureClassifier() {
    }

    public static Category classify(String errorText) {
        if (errorText == null || errorText.isBlank()) {
            return Category.UNKNOWN;
        }
        String text = errorText.toLowerCase(Locale.ROOT);
        if (matches(text, RATE_LIMIT_MARKERS)) {
            return Category.RATE_LIMIT;
        }
        if (matches(text, TIMEOUT_MARKERS)) {
            return Category.TIMEOUT;
        }
        if (matches(text, DENIED_MARKERS)) {
            return Category.PERMISSION_DENIED;
        }
        if (matches(text, NOT_FOUND_MARKERS)) {
            return Category.NOT_FOUND;
        }
        if (matches(text, MISSING_ARG_MARKERS)) {
            return Category.MISSING_ARG;
        }
        if (matches(text, EMPTY_MARKERS)) {
            return Category.EMPTY_RESULT;
        }
        return Category.UNKNOWN;
    }

    public static String signature(String toolName, Category category) {
        String tool = toolName == null || toolName.isBlank() ? "unknown_tool" : toolName.trim();
        return tool + ":" + category.name();
    }

    private static boolean matches(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
