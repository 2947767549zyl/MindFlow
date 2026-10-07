package com.mindflow.harness.hitl;

import java.util.Map;

/**
 * 审批请求（WS approval_request 载荷）与响应（WS approval_response 载荷）。
 * decision: approve / reject / approve_all / skip；modifiedArgs 仅 approve 时可携带。
 */
public final class HitlMessages {

    public record ApprovalRequest(
            String approvalId,
            String tool,
            String toolCallId,
            String riskLevel,
            String riskReason,
            Map<String, Object> args,
            long timeoutSeconds,
            String serverName
    ) {}

    public enum Decision {
        APPROVE,
        REJECT,
        APPROVE_ALL,
        SKIP;

        public static Decision parse(String raw) {
            if (raw == null) {
                return null;
            }
            return switch (raw.trim().toLowerCase()) {
                case "approve", "y" -> APPROVE;
                case "reject", "n" -> REJECT;
                case "approve_all", "a" -> APPROVE_ALL;
                case "skip", "s" -> SKIP;
                default -> null;
            };
        }
    }

    public record ApprovalResponse(
            String approvalId,
            Decision decision,
            Map<String, Object> modifiedArgs,
            String reason
    ) {}
}
