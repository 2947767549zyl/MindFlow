package com.mindflow.harness.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * L1 规则网关：把 CommandGuard（命令黑名单）与 PathGuard（路径围栏）接到工具执行入口上，
 * 位置在 HITL（L2）之前 fast-fail —— 不让注定要拒绝的东西占用用户注意力。
 *
 * 作用域只覆盖 mcp__ 前缀的外置工具：内置 8 个工具全部是只读检索或仅写自有存储
 * （Redis hash / 自身 MySQL 行），不具备文件系统与 shell 能力，套围栏属纯冗余开销。
 *
 * 与 L2 的关键差别是**本层常驻**：不受 mindflow.hitl.enabled 影响。即使审批关闭，
 * 破坏性命令与越界路径依然会被拦掉，避免"关审批 = 关防御"。
 */
@Component
public class ToolPolicyGateway {

    private static final Logger logger = LoggerFactory.getLogger(ToolPolicyGateway.class);

    /** 命中即视为命令类参数（按参数名小写子串匹配） */
    private static final Set<String> COMMAND_MARKERS = Set.of("command", "cmd", "script", "shell", "exec");

    /** 命中即视为路径类参数（值还需"看起来像路径"才真正校验，避免误伤自由文本） */
    private static final Set<String> PATH_MARKERS = Set.of("path", "file", "dir", "folder");

    /** 命中即跳过路径校验：这些参数是正则/表达式，形如 `.*\.java` 会被 looksLikePath 误判成路径 */
    private static final Set<String> PATH_EXCLUDED_MARKERS = Set.of("pattern", "regex", "expression", "query");

    /** 内置的文件类工具同样纳入围栏（原先只覆盖 mcp__ 前缀，会让内置读文件绕过 L1） */
    private static final Set<String> FENCED_BUILTIN_TOOLS = Set.of("read_file", "grep", "glob");

    /** 参数结构递归深度上限，防御异常嵌套 */
    private static final int MAX_DEPTH = 8;

    /** 硬拒绝的敏感路径片段（小写、反斜杠归一化后按 contains 匹配） */
    private static final List<String> DEFAULT_BLACKLIST_FRAGMENTS = List.of(
            "c:\\windows",
            "c:\\program files",
            "c:\\programdata",
            "\\.ssh",
            "\\.aws",
            "\\.gnupg",
            "\\.kube",
            "\\appdata\\roaming\\microsoft\\credentials",
            "\\appdata\\local\\microsoft\\credentials",
            "\\appdata\\local\\google\\chrome\\user data",
            "\\appdata\\roaming\\mozilla\\firefox"
    );

    private static final Set<String> BLACKLIST_FILE_NAMES = Set.of(
            ".env", "id_rsa", "id_ed25519", ".npmrc", ".pypirc", "credentials", "shadow", "sam", "ntuser.dat"
    );
    private static final Set<String> BLACKLIST_EXTENSIONS = Set.of(".pem", ".key", ".pfx", ".p12");

    /** 违规分类：BLACKLIST=命中敏感黑名单，硬拒绝（不给用户选择）；TRAVERSAL=越界，交由 L2 人工裁决 */
    public enum ViolationKind {
        BLACKLIST,
        TRAVERSAL
    }

    public record Violation(ViolationKind kind, String reason) {}

    private final boolean enabled;
    private final boolean pathGuardEnabled;
    private final String rootCandidate;
    private final List<String> blacklistFragments;

    private volatile PathGuard pathGuard;

    public ToolPolicyGateway(@Value("${mindflow.policy.guard.enabled:true}") boolean enabled,
                             @Value("${mindflow.policy.guard.path-enabled:true}") boolean pathGuardEnabled,
                             @Value("${mindflow.policy.guard.root:}") String configuredRoot,
                             @Value("${mindflow.mcp.project-dir:}") String mcpProjectDir,
                             @Value("${mindflow.policy.guard.path-blacklist:}") String extraBlacklist) {
        this.enabled = enabled;
        this.pathGuardEnabled = pathGuardEnabled;
        this.rootCandidate = firstNonBlank(configuredRoot, mcpProjectDir);
        List<String> fragments = new ArrayList<>(DEFAULT_BLACKLIST_FRAGMENTS);
        if (extraBlacklist != null && !extraBlacklist.isBlank()) {
            for (String item : extraBlacklist.split(",")) {
                String normalized = item.trim().replace('/', '\\').toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty() && !fragments.contains(normalized)) {
                    fragments.add(normalized);
                }
            }
        }
        this.blacklistFragments = List.copyOf(fragments);
    }

    /**
     * @return null 表示放行；非 null 时由调用方按 {@link ViolationKind} 决定硬拒还是转 L2 审批
     */
    public Violation evaluate(String toolName, Map<String, Object> arguments) {
        if (!enabled || !isFencedTool(toolName) || arguments == null || arguments.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Violation violation = scan(entry.getKey(), entry.getValue(), 0);
            if (violation != null) {
                logger.warn("L1 规则拦截: tool={}, arg={}, kind={}, reason={}",
                        toolName, entry.getKey(), violation.kind(), violation.reason());
                return violation;
            }
        }
        return null;
    }

    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }

    public static boolean isFencedTool(String toolName) {
        return isMcpTool(toolName) || (toolName != null && FENCED_BUILTIN_TOOLS.contains(toolName));
    }

    /** 供内置文件工具复用同一套根目录与越界判定（单一事实来源，避免两处根目录不一致） */
    public java.nio.file.Path resolvePath(String input) {
        return pathGuard().resolveSafe(input);
    }

    private Violation scan(String rawKey, Object value, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Violation violation = scan(String.valueOf(entry.getKey()), entry.getValue(), depth + 1);
                if (violation != null) {
                    return violation;
                }
            }
            return null;
        }
        if (value instanceof Collection<?> list) {
            for (Object item : list) {
                Violation violation = scan(rawKey, item, depth + 1);
                if (violation != null) {
                    return violation;
                }
            }
            return null;
        }
        if (!(value instanceof String text)) {
            return null;
        }

        String key = rawKey == null ? "" : rawKey.toLowerCase(Locale.ROOT);
        if (containsAny(key, COMMAND_MARKERS)) {
            String reason = CommandGuard.check(text);
            if (reason != null) {
                return new Violation(ViolationKind.BLACKLIST, "参数 `" + rawKey + "` 命中命令黑名单：" + reason);
            }
        }
        if (pathGuardEnabled && containsAny(key, PATH_MARKERS) && !containsAny(key, PATH_EXCLUDED_MARKERS)
                && looksLikePath(text)) {
            String normalized = text.trim().replace('/', '\\').toLowerCase(Locale.ROOT);
            String blacklisted = blacklistReason(normalized);
            if (blacklisted != null) {
                return new Violation(ViolationKind.BLACKLIST,
                        "参数 `" + rawKey + "` 命中敏感路径黑名单（" + blacklisted + "），该路径不可访问");
            }
            try {
                pathGuard().resolveSafe(text);
            } catch (PolicyException e) {
                return new Violation(ViolationKind.TRAVERSAL, "参数 `" + rawKey + "` 路径越界：" + e.getMessage());
            }
        }
        return null;
    }

    private String blacklistReason(String normalizedPath) {
        for (String fragment : blacklistFragments) {
            if (normalizedPath.contains(fragment)) {
                return "规则 " + fragment;
            }
        }
        int slash = normalizedPath.lastIndexOf('\\');
        String fileName = slash >= 0 ? normalizedPath.substring(slash + 1) : normalizedPath;
        if (BLACKLIST_FILE_NAMES.contains(fileName)) {
            return "敏感文件名 " + fileName;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && BLACKLIST_EXTENSIONS.contains(fileName.substring(dot))) {
            return "敏感扩展名 " + fileName.substring(dot);
        }
        return null;
    }

    /** 围栏根目录：mindflow.policy.guard.root → mindflow.mcp.project-dir → user.dir */
    private PathGuard pathGuard() {
        PathGuard guard = pathGuard;
        if (guard == null) {
            guard = new PathGuard(rootCandidate == null ? System.getProperty("user.dir") : rootCandidate);
            pathGuard = guard;
        }
        return guard;
    }

    private static boolean containsAny(String key, Set<String> markers) {
        for (String marker : markers) {
            if (key.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只有"看起来像路径"的值才做围栏校验：绝对路径、UNC、盘符、相对路径写法，
     * 或含路径分隔符 / ".."。这样 key 名恰好含 file/dir 子串的自由文本
     * （如 profile、folder 描述文案）不会被误拦。
     */
    private static boolean looksLikePath(String value) {
        String v = value.trim();
        if (v.isEmpty()) {
            return false;
        }
        if (v.startsWith("/") || v.startsWith("\\") || v.startsWith("~")) {
            return true;
        }
        if (v.length() >= 2 && v.charAt(1) == ':' && Character.isLetter(v.charAt(0))) {
            return true;
        }
        return v.startsWith("./") || v.startsWith("../") || v.contains("..")
                || v.contains("/") || v.contains("\\");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b != null && !b.isBlank() ? b.trim() : null;
    }
}
