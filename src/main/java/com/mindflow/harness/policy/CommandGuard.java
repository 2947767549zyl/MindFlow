package com.mindflow.harness.policy;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 命令快速拒绝：在命令类调用进入 HITL 审批 / 真正调用 ProcessBuilder 之前的黑名单 fast-fail
 * （移植自 MindFlow policy.CommandGuard）。
 *
 * 定位：辅助 HITL 而非主防线。黑名单是出名的反模式（永远列不全），但能拦住 LLM 容易踩的
 * 明显破坏性命令，减少 HITL 弹窗骚扰。真正的安全责任在 HITL 审批和用户判断。
 */
public final class CommandGuard {

    private static final List<DenyRule> RULES = List.of(
            new DenyRule("禁止 sudo 提权",
                    Pattern.compile("(?i)\\bsudo\\b")),
            new DenyRule("禁止 rm -rf 删除全盘或用户目录",
                    Pattern.compile("(?i)\\brm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+(/|~|\\$home)|" +
                            "\\brm\\s+-[a-z]*f[a-z]*r[a-z]*\\s+(/|~|\\$home)")),
            new DenyRule("禁止 mkfs 格式化磁盘",
                    Pattern.compile("(?i)\\bmkfs(\\.|\\b)")),
            new DenyRule("禁止 dd 写入裸设备",
                    Pattern.compile("(?i)\\bdd\\b[^\\n]*\\bof=/dev/")),
            new DenyRule("识别为 fork bomb",
                    Pattern.compile(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:")),
            new DenyRule("禁止 curl / wget 管道直接执行远端脚本",
                    Pattern.compile("(?i)\\b(curl|wget)\\b[^|\\n]*\\|\\s*(sh|bash|zsh|fish|ksh)\\b")),
            new DenyRule("不允许扫描 /、~ 或整个文件系统",
                    Pattern.compile("(?i)\\bfind\\s+(/|~|\\$home)")),
            new DenyRule("禁止 chmod 777 全盘",
                    Pattern.compile("(?i)\\bchmod\\s+-R\\s+777\\s+(/|~)")),
            new DenyRule("禁止 shutdown / reboot / halt",
                    Pattern.compile("(?i)\\b(shutdown|reboot|halt|poweroff)\\b"))
    );

    private CommandGuard() {
    }

    /**
     * @return null 表示放行；非 null 字符串是拒绝原因，调用方包装成用户/LLM 可见的提示
     */
    public static String check(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = command.replaceAll("\\s+", " ").trim();

        for (DenyRule rule : RULES) {
            if (rule.pattern().matcher(normalized).find()) {
                return rule.reason();
            }
        }
        return null;
    }

    private record DenyRule(String reason, Pattern pattern) {
    }
}
