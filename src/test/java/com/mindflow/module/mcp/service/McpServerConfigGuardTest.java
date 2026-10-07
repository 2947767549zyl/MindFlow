package com.mindflow.module.mcp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerConfigGuardTest {

    private McpServerConfigGuard guard;

    @BeforeEach
    void setUp() {
        guard = new McpServerConfigGuard();
    }

    @Test
    void acceptsValidStdioConfig() {
        assertDoesNotThrow(() -> guard.validate(
                "chrome-devtools", "stdio", "npx", List.of("-y", "chrome-devtools-mcp@latest"), null));
    }

    @Test
    void acceptsValidHttpConfig() {
        assertDoesNotThrow(() -> guard.validate(
                "remote-mcp", "http", null, List.of(), "https://mcp.example.com/sse"));
    }

    @Test
    void rejectsInvalidServerName() {
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "bad name!", "stdio", "npx", List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "-leading", "stdio", "npx", List.of(), null));
    }

    @Test
    void rejectsMissingOrConflictingTransport() {
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "stdio", null, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "http", null, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "stdio", "npx", List.of(), "https://x.com"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "http", "npx", List.of(), "https://x.com"));
    }

    @Test
    void rejectsCommandOutsideWhitelist() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "stdio", "bash", List.of("-c", "curl evil"), null));
        assertTrue(error.getMessage().contains("白名单"));
    }

    @Test
    void rejectsShellInjectionTokens() {
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "stdio", "npx", List.of("-y", "pkg", "&&", "rm -rf /"), null));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "stdio", "npx", List.of("$(whoami)"), null));
    }

    @Test
    void rejectsInterpreterInlineEval() {
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "stdio", "python3", List.of("-c", "print(1)"), null));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "stdio", "node", List.of("-e", "process.exit(0)"), null));
    }

    @Test
    void rejectsSsrfTargets() {
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "http", null, List.of(), "http://localhost:8080/mcp"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "http", null, List.of(), "http://127.0.0.1/mcp"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "http", null, List.of(), "http://192.168.1.10/mcp"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "http", null, List.of(), "http://10.0.0.5/mcp"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "http", null, List.of(), "http://169.254.169.254/latest/meta-data"));
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "http", null, List.of(), "ftp://example.com/mcp"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(
                "s", "http", null, List.of(), "file:///etc/passwd"));
    }
}
