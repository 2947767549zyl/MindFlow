package com.mindflow.harness.hitl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalPolicyTest {

    private ApprovalPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ApprovalPolicy();
        ReflectionTestUtils.setField(policy, "enabled", true);
    }

    @Test
    void disabledPolicyNeverRequiresApproval() {
        ReflectionTestUtils.setField(policy, "enabled", false);
        assertNull(policy.assess("mcp__demo__click"));
        assertNull(policy.assess("execute_command"));
    }

    @Test
    void mcpToolsAlwaysRequireApprovalWithServerName() {
        ApprovalPolicy.RiskAssessment risk = policy.assess("mcp__chrome-devtools__click");
        assertEquals("HIGH", risk.riskLevel());
        assertEquals("chrome-devtools", risk.serverName());
        assertTrue(risk.requiresApproval());
    }

    @Test
    void commandExecutionIsHighRisk() {
        ApprovalPolicy.RiskAssessment risk = policy.assess("execute_command");
        assertEquals("HIGH", risk.riskLevel());
        assertNull(risk.serverName());
    }

    @Test
    void trustedReadOnlyToolsSkipApproval() {
        assertNull(policy.assess("search_knowledge"));
        assertNull(policy.assess("search_wiki"));
        assertNull(policy.assess("navigate_wiki"));
        assertNull(policy.assess("knowledge_stats"));
    }
}
