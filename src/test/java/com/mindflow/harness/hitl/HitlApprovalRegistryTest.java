package com.mindflow.harness.hitl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitlApprovalRegistryTest {

    private HitlApprovalRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new HitlApprovalRegistry();
        ReflectionTestUtils.setField(registry, "approvalTimeoutSeconds", 1L);
    }

    private HitlMessages.ApprovalRequest request(String approvalId) {
        return new HitlMessages.ApprovalRequest(approvalId, "mcp__demo__click", null,
                "HIGH", "test", Map.of(), 1L, "demo");
    }

    @Test
    void completeResolvesAwaitWithDecision() {
        registry.create(request("a1"));
        assertTrue(registry.complete("a1", new HitlMessages.ApprovalResponse(
                "a1", HitlMessages.Decision.APPROVE, null, null)));

        HitlMessages.ApprovalResponse response = registry.await("a1");
        assertEquals(HitlMessages.Decision.APPROVE, response.decision());
    }

    @Test
    void completeUnknownApprovalIsNoop() {
        assertFalse(registry.complete("missing", new HitlMessages.ApprovalResponse(
                "missing", HitlMessages.Decision.APPROVE, null, null)));
        assertEquals(HitlMessages.Decision.REJECT, registry.await("missing").decision());
    }

    @Test
    void awaitWithoutResponseTimesOutAsReject() {
        registry.create(request("a2"));
        HitlMessages.ApprovalResponse response = registry.await("a2");
        assertEquals(HitlMessages.Decision.REJECT, response.decision());
        assertTrue(response.reason().contains("超时"));
    }

    @Test
    void approvedAllScopeCoversToolAndServer() {
        assertFalse(registry.isApprovedAll("conv-1", "mcp__demo__click", "demo"));

        registry.recordApprovedAll("conv-1", "mcp__demo__click", "demo");
        assertTrue(registry.isApprovedAll("conv-1", "mcp__demo__click", "demo"));
        assertTrue(registry.isApprovedAll("conv-1", "mcp__demo__other", "demo"));
        assertFalse(registry.isApprovedAll("conv-1", "execute_command", null));
        assertFalse(registry.isApprovedAll("conv-2", "mcp__demo__click", "demo"));

        registry.clearConversation("conv-1");
        assertFalse(registry.isApprovedAll("conv-1", "mcp__demo__click", "demo"));
    }

    @Test
    void decisionParsingIsLenientButSafe() {
        assertEquals(HitlMessages.Decision.APPROVE, HitlMessages.Decision.parse("approve"));
        assertEquals(HitlMessages.Decision.APPROVE_ALL, HitlMessages.Decision.parse("a"));
        assertEquals(HitlMessages.Decision.REJECT, HitlMessages.Decision.parse("n"));
        assertEquals(HitlMessages.Decision.SKIP, HitlMessages.Decision.parse("skip"));
        assertEquals(null, HitlMessages.Decision.parse("hmm"));
        assertEquals(null, HitlMessages.Decision.parse(null));
    }
}
