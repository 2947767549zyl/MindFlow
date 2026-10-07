package com.mindflow.harness.policy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolPolicyGatewayGuardTest {

    private static ToolPolicyGateway gateway(String root) {
        return new ToolPolicyGateway(true, true, root, null, null);
    }

    @Test
    void allowsPathInsideRoot() throws Exception {
        Path root = Files.createTempDirectory("mindflow-guard");
        Path inside = root.resolve("note.txt");
        Files.writeString(inside, "hi");

        ToolPolicyGateway guard = gateway(root.toString());

        assertNull(guard.evaluate("mcp__demo__read_text_file", Map.of("path", inside.toString())));
    }

    @Test
    void classifiesOutsideRootAsTraversal() throws Exception {
        Path root = Files.createTempDirectory("mindflow-guard");
        Path outside = Files.createTempDirectory("mindflow-outside").resolve("note.txt");
        Files.writeString(outside, "outside");

        ToolPolicyGateway guard = gateway(root.toString());
        ToolPolicyGateway.Violation violation =
                guard.evaluate("mcp__demo__read_text_file", Map.of("path", outside.toString()));

        assertNotNull(violation);
        assertEquals(ToolPolicyGateway.ViolationKind.TRAVERSAL, violation.kind());
    }

    @Test
    void classifiesPrivateKeyPathAsBlacklist() throws Exception {
        Path root = Files.createTempDirectory("mindflow-guard");

        ToolPolicyGateway guard = gateway(root.toString());
        ToolPolicyGateway.Violation violation =
                guard.evaluate("mcp__demo__read_text_file", Map.of("path", "C:\\Users\\Administrator\\.ssh\\id_rsa"));

        assertNotNull(violation);
        assertEquals(ToolPolicyGateway.ViolationKind.BLACKLIST, violation.kind());
    }

    @Test
    void classifiesSecretFileAsBlacklistEvenWhenInsideRoot() throws Exception {
        Path root = Files.createTempDirectory("mindflow-guard");
        Path secret = root.resolve(".env");
        Files.writeString(secret, "TOKEN=secret");

        ToolPolicyGateway guard = gateway(root.toString());
        ToolPolicyGateway.Violation violation =
                guard.evaluate("mcp__demo__read_text_file", Map.of("path", secret.toString()));

        assertNotNull(violation);
        assertEquals(ToolPolicyGateway.ViolationKind.BLACKLIST, violation.kind());
    }

    @Test
    void classifiesPemExtensionAsBlacklist() throws Exception {
        Path root = Files.createTempDirectory("mindflow-guard");

        ToolPolicyGateway guard = gateway(root.toString());
        ToolPolicyGateway.Violation violation =
                guard.evaluate("mcp__demo__read_text_file", Map.of("path", root.resolve("server.pem").toString()));

        assertNotNull(violation);
        assertEquals(ToolPolicyGateway.ViolationKind.BLACKLIST, violation.kind());
    }

    @Test
    void ignoresBuiltinTools() {
        ToolPolicyGateway guard = gateway("C:\\irrelevant");

        assertNull(guard.evaluate("search_knowledge", Map.of("path", "C:\\Windows\\System32\\config\\SAM")));
    }

    @Test
    void extraBlacklistFromConfigIsApplied() throws Exception {
        Path root = Files.createTempDirectory("mindflow-guard");
        ToolPolicyGateway guard = new ToolPolicyGateway(true, true, root.toString(), null, "D:\\corp-secrets");

        ToolPolicyGateway.Violation violation =
                guard.evaluate("mcp__demo__read_text_file", Map.of("path", "D:\\corp-secrets\\a.txt"));

        assertNotNull(violation);
        assertEquals(ToolPolicyGateway.ViolationKind.BLACKLIST, violation.kind());
    }

    @Test
    void fencesBuiltinFileToolsToo() throws Exception {
        Path root = Files.createTempDirectory("mindflow-guard");
        Path outside = Files.createTempDirectory("mindflow-outside").resolve("note.txt");
        Files.writeString(outside, "outside");

        ToolPolicyGateway guard = gateway(root.toString());
        ToolPolicyGateway.Violation violation = guard.evaluate("read_file", Map.of("path", outside.toString()));

        assertNotNull(violation);
        assertEquals(ToolPolicyGateway.ViolationKind.TRAVERSAL, violation.kind());
    }

    @Test
    void doesNotTreatRegexOrGlobPatternAsPath() {
        ToolPolicyGateway guard = gateway("C:\\irrelevant");

        assertNull(guard.evaluate("grep", Map.of("pattern", "class\\s+\\w+Service", "file_glob", "*.java")));
        assertNull(guard.evaluate("glob", Map.of("pattern", "**/*.java")));
    }
}
