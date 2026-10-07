package com.mindflow.harness.execute;

import com.mindflow.harness.hitl.HitlToolRegistry;
import com.mindflow.module.chat.agent.AgentToolRegistry;
import com.mindflow.structure.ai.LlmProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParallelToolRunnerTest {

    private static final ToolContext CTX = new ToolContext("u1", "conv-1", "gen-1");

    private HitlToolRegistry hitlToolRegistry;
    private AgentToolRegistry toolRegistry;
    private ParallelToolRunner runner;

    @BeforeEach
    void setUp() {
        hitlToolRegistry = mock(HitlToolRegistry.class);
        toolRegistry = mock(AgentToolRegistry.class);
        when(toolRegistry.getTools()).thenReturn(List.of(
                new AgentToolRegistry.AgentTool("t_ok", "demo", Map.of()),
                new AgentToolRegistry.AgentTool("t_slow", "demo", Map.of())
        ));
        runner = new ParallelToolRunner(hitlToolRegistry, toolRegistry,
                mock(com.mindflow.module.chat.service.ToolExperienceService.class));
        ReflectionTestUtils.setField(runner, "parallelism", 4);
        ReflectionTestUtils.setField(runner, "batchTimeoutSeconds", 5L);
    }

    private static LlmProviderRouter.ToolCallDecision call(String id, String name) {
        return new LlmProviderRouter.ToolCallDecision(id, name, Map.of());
    }

    private static AgentToolRegistry.ToolExecutionResult ok(String name, String content) {
        return new AgentToolRegistry.ToolExecutionResult(name, true, content, Map.of());
    }

    @Test
    void preservesInputOrderRegardlessOfCompletionOrder() {
        when(hitlToolRegistry.executeTool(eq(CTX), eq("t_ok"), any(), any()))
                .thenReturn(ok("t_ok", "first"));
        when(hitlToolRegistry.executeTool(eq(CTX), eq("t_slow"), any(), any()))
                .thenAnswer(inv -> {
                    Thread.sleep(150);
                    return ok("t_slow", "second");
                });

        List<ParallelToolRunner.ToolOutcome> outcomes = runner.executeBatch(
                CTX, List.of(call("c1", "t_slow"), call("c2", "t_ok")), chunk -> {});

        assertEquals(2, outcomes.size());
        assertEquals("c1", outcomes.get(0).toolCallId());
        assertEquals("second", outcomes.get(0).content());
        assertEquals("c2", outcomes.get(1).toolCallId());
        assertEquals("first", outcomes.get(1).content());
    }

    @Test
    void unknownToolReturnsStructuredErrorWithAvailableList() {
        List<ParallelToolRunner.ToolOutcome> outcomes = runner.executeBatch(
                CTX, List.of(call("c1", "t_hallucinated")), chunk -> {});

        assertEquals(1, outcomes.size());
        assertFalse(outcomes.get(0).success());
        assertTrue(outcomes.get(0).content().contains("t_hallucinated"));
        assertTrue(outcomes.get(0).content().contains("t_ok"));
    }

    @Test
    void singleToolFailureDoesNotBreakBatch() {
        when(hitlToolRegistry.executeTool(eq(CTX), eq("t_ok"), any(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(hitlToolRegistry.executeTool(eq(CTX), eq("t_slow"), any(), any()))
                .thenReturn(ok("t_slow", "fine"));

        List<ParallelToolRunner.ToolOutcome> outcomes = runner.executeBatch(
                CTX, List.of(call("c1", "t_ok"), call("c2", "t_slow")), chunk -> {});

        assertEquals(2, outcomes.size());
        assertFalse(outcomes.get(0).success());
        assertTrue(outcomes.get(0).content().contains("boom"));
        assertTrue(outcomes.get(1).success());
        assertEquals("fine", outcomes.get(1).content());
    }

    @Test
    void emptyBatchReturnsEmpty() {
        assertTrue(runner.executeBatch(CTX, List.of(), chunk -> {}).isEmpty());
    }
}
