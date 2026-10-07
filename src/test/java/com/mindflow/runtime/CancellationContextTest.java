package com.mindflow.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationContextTest {

    @Test
    void keyedTokensAreIndependent() {
        CancellationContext.startRun("gen-1");
        CancellationContext.startRun("gen-2");

        assertFalse(CancellationContext.isCancelled("gen-1"));
        assertFalse(CancellationContext.isCancelled("gen-2"));

        CancellationContext.cancel("gen-1");

        assertTrue(CancellationContext.isCancelled("gen-1"));
        assertFalse(CancellationContext.isCancelled("gen-2"));

        CancellationContext.clear("gen-1");
        assertFalse(CancellationContext.isCancelled("gen-1"));
        assertFalse(CancellationContext.isCancelled("gen-2"));
    }

    @Test
    void cancelUnknownKeyIsNoop() {
        assertFalse(CancellationContext.isCancelled("never-started"));
        CancellationContext.cancel("never-started");
        assertFalse(CancellationContext.isCancelled("never-started"));
    }
}
