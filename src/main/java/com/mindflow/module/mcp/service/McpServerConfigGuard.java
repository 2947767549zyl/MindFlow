package com.mindflow.module.mcp.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP server 配置安全校验（配置即 RCE 的护栏）。
 *
 * 说明：stdio 传输用 ProcessBuilder、不经 shell，因此参数中的 ; | && 等不会被执行；
 * 真正的风险是「命令本身」以及「npx/uvx 拉取并执行任意包」与「解释器 -c/-e 内联执行」。
 * 因此这里做：可执行文件白名单 + 内联执行开关拦截 + 参数注入特征拦截 + http 目标的 SSRF 粗拦。
 * 注意：白名单不能穷尽风险，主防线是「仅 ADMIN + 审计 + UI 警示」。
 */
@Component
public class McpServerConfigGuard {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$");
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "npx", "uvx", "pnpm", "bunx", "node", "python", "python3");
    private static final List<String> INJECTION_TOKENS = List.of("&&", "||", ";", "|", "`", "$(", ">");

    public void validate(String name, String transportType, String command, List<String> args, String url) {
        if (name == null || !NAME_PATTERN.matcher(name.trim()).matches()) {
            throw new IllegalArgumentException("server 名称只能是字母/数字/下划线/连字符，且以字母或数字开头（≤64 字符）");
        }
        boolean stdio = "stdio".equalsIgnoreCase(transportType);
        boolean http = "http".equalsIgnoreCase(transportType);
        if (stdio == http) {
            throw new IllegalArgumentException("transportType 必须是 stdio 或 http，且二选一");
        }

        if (stdio) {
            String trimmedCommand = command == null ? "" : command.trim();
            if (trimmedCommand.isEmpty()) {
                throw new IllegalArgumentException("stdio 模式必须提供 command");
            }
            if (url != null && !url.isBlank()) {
                throw new IllegalArgumentException("stdio 模式不允许配置 url");
            }
            String executable = basename(trimmedCommand).toLowerCase(Locale.ROOT);
            if (!ALLOWED_COMMANDS.contains(executable)) {
                throw new IllegalArgumentException("command 不在白名单内，允许: " + ALLOWED_COMMANDS
                        + "（如需其他可执行文件请联系管理员扩展白名单）");
            }
            validateArgs(executable, args == null ? List.of() : args);
        } else {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("http 模式必须提供 url");
            }
            if (command != null && !command.isBlank()) {
                throw new IllegalArgumentException("http 模式不允许配置 command");
            }
            validateUrl(url.trim());
        }
    }

    private void validateArgs(String executable, List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i) == null ? "" : args.get(i);
            for (String token : INJECTION_TOKENS) {
                if (arg.contains(token)) {
                    throw new IllegalArgumentException("参数包含可疑注入特征: " + token);
                }
            }
            boolean inlineEval = ("python".equals(executable) || "python3".equals(executable)) && "-c".equals(arg);
            inlineEval = inlineEval || "node".equals(executable) && ("-e".equals(arg) || "--eval".equals(arg));
            if (inlineEval) {
                throw new IllegalArgumentException("禁止解释器内联执行（-c / -e / --eval），请改用脚本文件");
            }
        }
    }

    private void validateUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("MCP url 只允许 http/https");
        }
        String host = lower.replaceFirst("^https?://", "");
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(']') < colon) {
            host = host.substring(0, colon);
        }
        host = host.replace("[", "").replace("]", "");
        if (host.isEmpty()) {
            throw new IllegalArgumentException("MCP url 缺少主机名");
        }
        if (host.equals("localhost") || host.equals("0.0.0.0") || host.equals("::1")
                || host.startsWith("127.") || host.startsWith("169.254.")
                || host.startsWith("10.") || host.startsWith("192.168.")
                || host.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*")) {
            throw new IllegalArgumentException("MCP url 不允许指向本机或内网地址（SSRF 防护）");
        }
    }

    private String basename(String command) {
        String normalized = command.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
