package com.mindflow.harness.hitl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * HITL 风险分级（L2 审批入口）：返回 null 表示无需审批，直接执行。
 * 当前 web 工具集全部为受信只读/受信执行；MCP 动态工具按背景文档口径一律强制审批。
 *
 * 审批范围与审计范围是两件事：assess() 受 mindflow.hitl.enabled 控制，
 * requiresAudit() 不受其影响 —— 审批关闭时仍需知道“哪些工具的动作要落审计（L3）”。
 */
@Service
public class ApprovalPolicy {

    /**
     * 需要入 L3 的内置工具：仅限有副作用的（写长期记忆、加载 Skill）。
     * search_* / knowledge_stats / generate_summary 这类纯只读检索不纳入，
     * 否则 audit_logs 会被检索事件刷满，稀释掉真正有追溯价值的记录。
     */
    private static final Set<String> AUDITED_BUILTIN_TOOLS = Set.of("save_memory", "load_skill");

    @Value("${mindflow.hitl.enabled:false}")
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public RiskAssessment assess(String toolName) {
        if (!enabled || toolName == null) {
            return null;
        }
        if (toolName.startsWith("mcp__")) {
            String[] parts = toolName.split("__", 3);
            String serverName = parts.length == 3 ? parts[1] : "unknown";
            return new RiskAssessment("HIGH", "MCP 外部 server 工具，默认强制审批", serverName);
        }
        return switch (toolName) {
            case "execute_command" -> new RiskAssessment("HIGH", "命令执行属高危操作", null);
            case "write_file", "create_project" -> new RiskAssessment("MEDIUM", "写入操作可能修改外部状态", null);
            default -> null;
        };
    }

    /**
     * L3 审计范围：MCP 外置工具全量记录，内置工具只记录有副作用的两个。
     * 与审批开关无关，保证默认配置（hitl.enabled=false）下外部工具调用仍可白盒追溯。
     */
    public boolean requiresAudit(String toolName) {
        if (toolName == null) {
            return false;
        }
        return toolName.startsWith("mcp__") || AUDITED_BUILTIN_TOOLS.contains(toolName);
    }

    public record RiskAssessment(String riskLevel, String riskReason, String serverName) {
        public boolean requiresApproval() {
            return riskLevel != null;
        }

        public Map<String, Object> toPayload() {
            return Map.of(
                    "riskLevel", riskLevel,
                    "riskReason", riskReason
            );
        }
    }
}
