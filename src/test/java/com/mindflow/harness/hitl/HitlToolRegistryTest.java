package com.mindflow.harness.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.harness.execute.ToolContext;
import com.mindflow.harness.policy.AuditLogService;
import com.mindflow.harness.policy.ToolPolicyGateway;
import com.mindflow.module.chat.agent.AgentToolExecutor;
import com.mindflow.module.chat.agent.AgentToolRegistry;
import com.mindflow.module.chat.handler.ChatStreamingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 安全网关接线测试，全部在 mindflow.hitl.enabled=false（默认值）下运行：
 * 验证 L1 拦截与 L3 补记录都不依赖审批开关，否则"三层防御"在默认配置下只剩零层。
 */
class HitlToolRegistryTest {

    private static final ToolContext CTX = new ToolContext("u1", "conv-1", "gen-1");

    private AgentToolExecutor toolExecutor;
    private AuditLogService auditLogService;
    private HitlToolRegistry registry;

    @BeforeEach
    void setUp() {
        // 真实 ApprovalPolicy：未注入 enabled，保持默认 false，所有工具都走非审批路径
        registry = new HitlToolRegistry(new ApprovalPolicy(),
                mock(HitlApprovalRegistry.class),
                toolExecutor = mock(AgentToolExecutor.class),
                mock(ChatStreamingService.class),
                auditLogService = mock(AuditLogService.class),
                new ToolPolicyGateway(true, true, System.getProperty("user.dir"), null, null),
                new ObjectMapper());
    }

    private static AgentToolRegistry.ToolExecutionResult ok(String name, String content) {
        return new AgentToolRegistry.ToolExecutionResult(name, true, content, Map.of());
    }

    @Test
    void l1BlocksMcpToolWithoutExecutingItAndAuditsDeny() {
        var result = registry.executeTool(CTX, "mcp__demo__shell",
                Map.of("command", "sudo rm -rf /"), chunk -> {});

        assertFalse(result.success());
        assertTrue(result.content().contains("安全策略拦截"), result.content());
        verifyNoInteractions(toolExecutor);
        verify(auditLogService).record(eq("mcp__demo__shell"), anyString(),
                eq(AuditLogService.OUTCOME_DENY),
                argThat(reason -> reason != null && reason.contains("L1")),
                eq(AuditLogService.APPROVER_NONE), anyLong(), eq("u1"), eq("conv-1"));
    }

    @Test
    void l1FencesMcpPathEscapeWithoutExecutingIt() {
        var result = registry.executeTool(CTX, "mcp__fs__write",
                Map.of("file_path", "../../../../etc/passwd"), chunk -> {});

        assertFalse(result.success());
        verifyNoInteractions(toolExecutor);
    }

    @Test
    void mcpToolOnNonApprovalPathIsAuditedWithApproverNone() {
        when(toolExecutor.executeTool(eq("mcp__fs__read"), any(), eq("u1"), any()))
                .thenReturn(ok("mcp__fs__read", "文档内容"));

        assertTrue(registry.executeTool(CTX, "mcp__fs__read",
                Map.of("file_path", "README.md"), chunk -> {}).success());

        verify(auditLogService).record(eq("mcp__fs__read"), anyString(),
                eq(AuditLogService.OUTCOME_ALLOW), isNull(),
                eq(AuditLogService.APPROVER_NONE), anyLong(), eq("u1"), eq("conv-1"));
    }

    @Test
    void sideEffectBuiltinIsAudited() {
        when(toolExecutor.executeTool(eq("save_memory"), any(), eq("u1"), any()))
                .thenReturn(ok("save_memory", "已保存"));

        registry.executeTool(CTX, "save_memory", Map.of("fact", "用户偏好中文回答"), chunk -> {});

        verify(auditLogService).record(eq("save_memory"), anyString(),
                eq(AuditLogService.OUTCOME_ALLOW), isNull(),
                eq(AuditLogService.APPROVER_NONE), anyLong(), eq("u1"), eq("conv-1"));
    }

    @Test
    void readOnlyBuiltinIsNotAuditedToKeepAuditTrailMeaningful() {
        when(toolExecutor.executeTool(eq("search_knowledge"), any(), eq("u1"), any()))
                .thenReturn(ok("search_knowledge", "检索结果"));

        assertTrue(registry.executeTool(CTX, "search_knowledge",
                Map.of("query", "Raft 选主"), chunk -> {}).success());

        verifyNoInteractions(auditLogService);
    }

    @Test
    void failedMcpExecutionIsAuditedAsError() {
        when(toolExecutor.executeTool(eq("mcp__demo__query"), any(), eq("u1"), any()))
                .thenReturn(new AgentToolRegistry.ToolExecutionResult(
                        "mcp__demo__query", false, "MCP 工具执行失败: timeout", Map.of()));

        registry.executeTool(CTX, "mcp__demo__query", Map.of("q", "abc"), chunk -> {});

        verify(auditLogService).record(eq("mcp__demo__query"), anyString(),
                eq(AuditLogService.OUTCOME_ERROR), eq("MCP 工具执行失败: timeout"),
                eq(AuditLogService.APPROVER_NONE), anyLong(), eq("u1"), eq("conv-1"));
    }

    @Test
    void auditScopeIsIndependentOfApprovalSwitch() {
        ApprovalPolicy policy = new ApprovalPolicy();
        assertFalse(policy.isEnabled());
        // 审批关闭时不弹审批窗，但审计范围不变 —— 这是 L3 补记录的意义
        assertNull(policy.assess("mcp__demo__shell"));
        assertTrue(policy.requiresAudit("mcp__demo__shell"));
        assertTrue(policy.requiresAudit("load_skill"));
        assertFalse(policy.requiresAudit("search_knowledge"));
        assertFalse(policy.requiresAudit("knowledge_stats"));
        assertFalse(policy.requiresAudit(null));
    }
}
