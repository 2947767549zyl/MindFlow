package com.mindflow.module.chat.agent;

import com.mindflow.harness.policy.ToolPolicyGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileToolsTest {

    private static LocalFileTools toolsFor(Path root) {
        return new LocalFileTools(new ToolPolicyGateway(true, true, root.toString(), null, null));
    }

    @Test
    void readFileReturnsNumberedContent(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("demo.txt"), "alpha\nbeta\ngamma\n");

        var result = toolsFor(root).execute("read_file", Map.of("path", "demo.txt"));

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("2| beta"), result.content());
    }

    @Test
    void readFileHonoursOffsetAndLimit(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("demo.txt"), "l1\nl2\nl3\nl4\n");

        var result = toolsFor(root).execute("read_file", Map.of("path", "demo.txt", "offset", 2, "limit", 1));

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("2| l2"), result.content());
        assertFalse(result.content().contains("3| l3"), result.content());
    }

    @Test
    void readFileRefusesPathOutsideRoot(@TempDir Path root, @TempDir Path outside) throws Exception {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "top secret");

        var result = toolsFor(root).execute("read_file", Map.of("path", secret.toString()));

        assertFalse(result.success());
        assertTrue(result.content().contains("路径越界"), result.content());
    }

    @Test
    void grepFindsMatchesWithRelativeLocation(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Alpha.java"), "class Alpha {\n  void run() {}\n}\n");

        var result = toolsFor(root).execute("grep", Map.of("pattern", "class\\s+\\w+"));

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("Alpha.java:1"), result.content());
    }

    @Test
    void grepSupportsFileGlobFilter(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("A.java"), "needle\n");
        Files.writeString(root.resolve("B.md"), "needle\n");

        var result = toolsFor(root).execute("grep", Map.of("pattern", "needle", "file_glob", "*.java"));

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("A.java"), result.content());
        assertFalse(result.content().contains("B.md"), result.content());
    }

    @Test
    void globMatchesOnlyMatchingFiles(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/App.java"), "class App {}\n");
        Files.writeString(root.resolve("readme.md"), "# readme\n");

        var result = toolsFor(root).execute("glob", Map.of("pattern", "**/*.java"));

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("App.java"), result.content());
        assertFalse(result.content().contains("readme.md"), result.content());
    }

    @Test
    void globRejectsParentTraversalPattern(@TempDir Path root) {
        var result = toolsFor(root).execute("glob", Map.of("pattern", "../../*.env"));

        assertFalse(result.success());
    }

    @Test
    void readFileRefusesSecretFileInsideRoot(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("app.env.txt"), "TOKEN=1\n");

        var result = toolsFor(root).execute("read_file", Map.of("path", ".env"));

        assertFalse(result.success());
    }
}
