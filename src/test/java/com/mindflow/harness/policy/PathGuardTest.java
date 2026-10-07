package com.mindflow.harness.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void allowsRelativePathInsideRoot() {
        PathGuard guard = new PathGuard(tempDir.toString());

        Path safe = guard.resolveSafe("sub/dir/file.txt");

        assertTrue(safe.startsWith(guard.getRootPath()));
    }

    @Test
    void rejectsDotDotTraversal() {
        PathGuard guard = new PathGuard(tempDir.toString());

        PolicyException ex = assertThrows(PolicyException.class,
                () -> guard.resolveSafe("../outside.txt"));
        assertTrue(ex.getMessage().contains("路径越界"));
    }

    @Test
    void rejectsAbsolutePathOutsideRoot() {
        PathGuard guard = new PathGuard(tempDir.toString());

        String outside = tempDir.getParent().resolve("elsewhere.txt").toString();
        assertThrows(PolicyException.class, () -> guard.resolveSafe(outside));
    }

    @Test
    void allowsPathInsideRootWhoseFileDoesNotExistYet() {
        PathGuard guard = new PathGuard(tempDir.toString());

        Path safe = guard.resolveSafe("not/created/yet.md");

        assertTrue(safe.startsWith(guard.getRootPath()));
    }

    @Test
    void rejectsBlankInput() {
        PathGuard guard = new PathGuard(tempDir.toString());
        assertThrows(PolicyException.class, () -> guard.resolveSafe(" "));
    }

    @Test
    void requiresNonBlankRoot() {
        assertEquals("项目根路径不能为空",
                assertThrows(IllegalArgumentException.class, () -> new PathGuard(" "))
                        .getMessage());
    }
}
