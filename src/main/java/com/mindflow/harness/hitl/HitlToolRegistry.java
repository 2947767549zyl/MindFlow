package com.mindflow.harness.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.harness.execute.ToolContext;
import com.mindflow.harness.policy.AuditLogService;
import com.mindflow.harness.policy.ToolPolicyGateway;
import com.mindflow.module.chat.agent.AgentToolExecutor;
import com.mindflow.module.chat.handler.ChatStreamingService;
import com.mindflow.module.chat.agent.AgentToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 工具执行安全网关：L1 规则过滤 + HITL 人工审批（L2）+ 审计落盘（L3）。
 *
 * L1 由 {@link ToolPolicyGateway} 承担，位于本类最前、仅作用于 mcp__ 外置工具，
 * 且**不受 mindflow.hitl.enabled 影响**：审批关闭时破坏性命令与越界路径依旧会被拦掉。
 *
 * L2 关闭时与非审批路径完全一致（fail-open 与 MindFlow CLI 行为对齐）；开启时对策略命中工具
 * 透明拦截：WS 推 approval_request → 挂起等待 → 按决策放行/修改参数/拒绝/跳过。
 * 审批允许改参，改后的参数会再过一遍 L1，避免"人工批准"变成"越界放行"。
 *
 * L3 记录范围见 {@link ApprovalPolicy#requiresAudit}：MCP 工具全量 + 有副作用的内置工具，
 * 无论审批与否都由 AuditLogService 双写（JSONL+MySQL）并脱敏；save_memory/load_skill 成功后推 WS 副作用事件。
 */
@Service
public class HitlToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(HitlToolRegistry.class);

    private final ApprovalPolicy approvalPolicy;
    private final HitlApprovalRegistry approvalRegistry;
    private final AgentToolExecutor toolExecutor;
    private final ChatStreamingService streamingService;
    private final AuditLogService auditLogService;
    private final ToolPolicyGateway policyGateway;
    private final ObjectMapper objectMapper;

    public HitlToolRegistry(ApprovalPolicy approvalPolicy,
                            HitlApprovalRegistry approvalRegistry,
                            AgentToolExecutor toolExecutor,
                            ChatStreamingService streamingService,
                            AuditLogService auditLogService,
                            ToolPolicyGateway policyGateway,
                            ObjectMapper objectMapper) {
        this.approvalPolicy = approvalPolicy;
        this.approvalRegistry = approvalRegistry;
        this.toolExecutor = toolExecutor;
        this.streamingService = streamingService;
        this.auditLogService = auditLogService;
        this.policyGateway = policyGateway;
        this.objectMapper = objectMapper;
    }

    public AgentToolRegistry.ToolExecutionResult executeTool(ToolContext ctx,
                                                             String name,
                                                             Map<String, Object> arguments,
                                                             Consumer<String> onChunk) {
        // L1：命令/敏感路径黑名单硬拒（不给用户选择）；仅"路径越界"转 L2 人工裁决。
        // 若审批被关闭则没有裁决通道，越界退回硬拒 —— 保证"关审批"不等于"放行越界"。
        ToolPolicyGateway.Violation violation = policyGateway.evaluate(name, arguments);
        if (violation != null && violation.kind() == ToolPolicyGateway.ViolationKind.BLACKLIST) {
            auditLogService.record(name, argsJson(arguments), AuditLogService.OUTCOME_DENY,
                    "L1 黑名单拦截: " + violation.reason(), AuditLogService.APPROVER_NONE, 0,
                    ctx.userId(), ctx.conversationId());
            return policyDenied(name, violation.reason());
        }

        ApprovalPolicy.RiskAssessment risk = approvalPolicy.assess(name);
        // 审批通道关闭时没人能裁决越界，退回硬拒 —— 保证"关审批"不等于"放行越界"。
        if (violation != null && !approvalPolicy.isEnabled()) {
            auditLogService.record(name, argsJson(arguments), AuditLogService.OUTCOME_DENY,
                    "L1 路径越界且无审批通道: " + violation.reason(), AuditLogService.APPROVER_NONE, 0,
                    ctx.userId(), ctx.conversationId());
            return policyDenied(name, violation.reason());
        }
        // 路径越界本身就构成"必须有人裁决"的理由，即使该工具的风险分级未要求审批。
        boolean approvalRequired = (risk != null && risk.requiresApproval()) || violation != null;
        if (!approvalRequired) {
            long startedDirect = System.currentTimeMillis();
            AgentToolRegistry.ToolExecutionResult result =
                    toolExecutor.executeTool(name, arguments, ctx.userId(), onChunk);
            // L3 补记录：非审批路径此前完全不落审计，导致 hitl 关闭时"白盒追溯"不成立。
            // 范围只含 MCP 工具与有副作用的内置工具，纯只读检索不记，避免审计表被刷满。
            if (approvalPolicy.requiresAudit(name)) {
                auditLogService.record(name, argsJson(arguments),
                        result.success() ? AuditLogService.OUTCOME_ALLOW : AuditLogService.OUTCOME_ERROR,
                        result.success() ? null : result.content(),
                        AuditLogService.APPROVER_NONE, System.currentTimeMillis() - startedDirect,
                        ctx.userId(), ctx.conversationId());
            }
            maybeNotifySideEffects(ctx, result);
            return result;
        }

        if (approvalRegistry.isApprovedAll(ctx.conversationId(), name, serverNameOf(risk))) {
            long startedAll = System.currentTimeMillis();
            AgentToolRegistry.ToolExecutionResult result =
                    toolExecutor.executeTool(name, arguments, ctx.userId(), onChunk);
            auditLogService.record(name, argsJson(arguments),
                    result.success() ? AuditLogService.OUTCOME_ALLOW : AuditLogService.OUTCOME_ERROR,
                    result.success() ? null : result.content(),
                    AuditLogService.APPROVER_HITL, System.currentTimeMillis() - startedAll,
                    ctx.userId(), ctx.conversationId());
            maybeNotifySideEffects(ctx, result);
            return result;
        }

        String approvalId = UUID.randomUUID().toString();
        String riskReason = violation == null
                ? (risk == null ? "该工具调用需要人工确认" : risk.riskReason())
                : violation.reason() + " —— 该路径超出默认允许范围，请确认是否允许本次访问";
        HitlMessages.ApprovalRequest request = new HitlMessages.ApprovalRequest(
                approvalId, name, null, violation == null && risk != null ? risk.riskLevel() : "HIGH", riskReason,
                arguments, approvalRegistry.approvalTimeoutSeconds(), serverNameOf(risk));

        approvalRegistry.create(request);
        streamingService.sendApprovalRequest(ctx.userId(), ctx.generationId(), ctx.conversationId(), request);
        logger.info("HITL 审批请求已推送: approvalId={}, tool={}, conversationId={}",
                approvalId, name, ctx.conversationId());

        long started = System.currentTimeMillis();
        HitlMessages.ApprovalResponse response = approvalRegistry.await(approvalId);
        long durationMs = System.currentTimeMillis() - started;

        String postApprovalViolation = null;
        AgentToolRegistry.ToolExecutionResult result = switch (response.decision()) {
            case APPROVE_ALL -> {
                approvalRegistry.recordApprovedAll(ctx.conversationId(), name, serverNameOf(risk));
                yield toolExecutor.executeTool(name, arguments, ctx.userId(), onChunk);
            }
            case APPROVE -> {
                Map<String, Object> effectiveArgs = (response.modifiedArgs() == null || response.modifiedArgs().isEmpty())
                        ? arguments
                        : response.modifiedArgs();
                ToolPolicyGateway.Violation edited = policyGateway.evaluate(name, effectiveArgs);
                // 审批通过即视为用户已授权本次越界，因此这里只拦黑名单；否则"批准"会被同一条越界规则再拒一次。
                if (edited != null && edited.kind() == ToolPolicyGateway.ViolationKind.BLACKLIST) {
                    postApprovalViolation = edited.reason();
                    yield policyDenied(name, edited.reason());
                }
                yield toolExecutor.executeTool(name, effectiveArgs, ctx.userId(), onChunk);
            }
            case SKIP -> skipped(name, "用户选择跳过本步骤");
            case REJECT -> rejected(name, response.reason() == null ? "用户拒绝该操作" : response.reason());
        };

        switch (response.decision()) {
            case APPROVE, APPROVE_ALL -> {
                if (postApprovalViolation != null) {
                    auditLogService.record(name, argsJson(arguments), AuditLogService.OUTCOME_DENY,
                            "审批后参数命中 L1: " + postApprovalViolation,
                            AuditLogService.APPROVER_HITL, durationMs, ctx.userId(), ctx.conversationId());
                } else {
                    String approveReason = violation != null
                            ? "用户批准越界访问: " + violation.reason()
                            : (result.success() ? null : result.content());
                    auditLogService.record(name, argsJson(arguments),
                            result.success() ? AuditLogService.OUTCOME_ALLOW : AuditLogService.OUTCOME_ERROR,
                            approveReason,
                            AuditLogService.APPROVER_HITL, durationMs, ctx.userId(), ctx.conversationId());
                }
            }
            case SKIP, REJECT -> auditLogService.record(name, argsJson(arguments),
                    AuditLogService.OUTCOME_DENY, response.reason(),
                    AuditLogService.APPROVER_HITL, durationMs, ctx.userId(), ctx.conversationId());
        }

        maybeNotifySideEffects(ctx, result);
        return result;
    }

    private static String serverNameOf(ApprovalPolicy.RiskAssessment risk) {
        return risk == null ? null : risk.serverName();
    }

    private void maybeNotifySideEffects(ToolContext ctx, AgentToolRegistry.ToolExecutionResult result) {
        if (!result.success() || result.data() == null) {
            return;
        }
        if ("save_memory".equals(result.toolName()) && result.data().get("fact") != null) {
            streamingService.sendMemorySaved(ctx.userId(), ctx.generationId(), ctx.conversationId(),
                    String.valueOf(result.data().get("fact")));
        }
        if ("load_skill".equals(result.toolName()) && result.data().get("name") != null
                && Boolean.TRUE.equals(result.data().get("loaded"))) {
            streamingService.sendSkillLoaded(ctx.userId(), ctx.generationId(), ctx.conversationId(),
                    String.valueOf(result.data().get("name")));
        }
    }

    private String argsJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception e) {
            return String.valueOf(arguments);
        }
    }

    private AgentToolRegistry.ToolExecutionResult rejected(String name, String reason) {
        return new AgentToolRegistry.ToolExecutionResult(name, false,
                "🛡️ 已拒绝: " + reason + "。被拒绝的工具调用不要原样重试，请调整方案或向用户说明。",
                new LinkedHashMap<>());
    }

    private AgentToolRegistry.ToolExecutionResult skipped(String name, String reason) {
        return new AgentToolRegistry.ToolExecutionResult(name, false,
                "⏭️ 已跳过: " + reason + "。如该步骤必要，请在最终回答中说明。",
                new LinkedHashMap<>());
    }

    /** L1 拦截结果：措辞与 rejected 对齐，明确告知 LLM 不要重试该调用 */
    private AgentToolRegistry.ToolExecutionResult policyDenied(String name, String violation) {
        return new AgentToolRegistry.ToolExecutionResult(name, false,
                "🚫 已被安全策略拦截: " + violation + "。此调用不会重试，请改用已有的检索结果作答，或向用户说明无法完成。",
                new LinkedHashMap<>());
    }
}
