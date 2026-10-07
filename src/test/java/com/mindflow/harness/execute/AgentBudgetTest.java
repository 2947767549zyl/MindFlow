package com.mindflow.harness.execute;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentBudgetTest {

    private static List<AgentBudget.ToolCallSignature> sigs(String name, String args) {
        return List.of(new AgentBudget.ToolCallSignature(name, args));
    }

    @Test
    void withinBudgetWhenNothingRecorded() {
        AgentBudget budget = new AgentBudget(100, 3, 10);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
        assertEquals(0, budget.iteration());
    }

    @Test
    void hardIterationLimitTriggers() {
        AgentBudget budget = new AgentBudget(Integer.MAX_VALUE, 3, 3);
        budget.beginIteration();
        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.HARD_ITERATION_LIMIT, budget.check());
    }

    @Test
    void tokenBudgetExceeded() {
        AgentBudget budget = new AgentBudget(100, 3, 50);
        budget.recordTokens(60, 30, 0);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
        budget.recordTokens(5, 6, 0);
        assertEquals(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED, budget.check());
    }

    @Test
    void stagnationDetectedAfterRepeatedIdenticalToolCalls() {
        AgentBudget budget = new AgentBudget(Integer.MAX_VALUE, 3, 50);
        budget.recordToolCalls(sigs("search_knowledge", "{\"query\":\"a\"}"));
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
        budget.recordToolCalls(sigs("search_knowledge", "{\"query\":\"a\"}"));
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
        budget.recordToolCalls(sigs("search_knowledge", "{\"query\":\"a\"}"));
        assertEquals(AgentBudget.ExitReason.STAGNATION_DETECTED, budget.check());
    }

    @Test
    void differentArgsResetStagnationWindow() {
        AgentBudget budget = new AgentBudget(Integer.MAX_VALUE, 3, 50);
        budget.recordToolCalls(sigs("search_knowledge", "{\"query\":\"a\"}"));
        budget.recordToolCalls(sigs("search_knowledge", "{\"query\":\"a\"}"));
        budget.recordToolCalls(sigs("search_knowledge", "{\"query\":\"b\"}"));
        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void emptyToolCallListClearsWindow() {
        AgentBudget budget = new AgentBudget(Integer.MAX_VALUE, 3, 50);
        budget.recordToolCalls(sigs("t", "{}"));
        budget.recordToolCalls(List.of());
        budget.recordToolCalls(sigs("t", "{}"));
        budget.recordToolCalls(sigs("t", "{}"));
        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
        budget.recordToolCalls(sigs("t", "{}"));
        assertEquals(AgentBudget.ExitReason.STAGNATION_DETECTED, budget.check());
    }

    @Test
    void constructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(0, 3, 10));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(100, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(100, 3, 0));
    }
}
