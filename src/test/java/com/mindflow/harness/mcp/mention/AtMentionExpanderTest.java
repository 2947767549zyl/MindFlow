package com.mindflow.harness.mcp.mention;

import com.mindflow.harness.mcp.resources.McpResourceReadResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class AtMentionExpanderTest {
    @Test
    void replacesMentionWithResourceBlock() {
        AtMentionExpander expander = new AtMentionExpander(
                (server, uri) -> new McpResourceReadResult("hello", "text/plain"));

        String expanded = expander.expand("看 @fs:file://README.md");

        assertTrue(expanded.contains("<resource server=\"fs\" uri=\"file://README.md\" mimeType=\"text/plain\">"));
        assertTrue(expanded.contains("hello"));
        assertFalse(expanded.contains("@fs:file://README.md"));
    }

    @Test
    void expandsMultipleMentionsFromRightToLeft() {
        AtMentionExpander expander = new AtMentionExpander(
                (server, uri) -> new McpResourceReadResult("body", "text/plain"));

        String expanded = expander.expand("@fs:file://a 和 @fs:file://b");

        assertEquals(2, count(expanded, "<resource server=\"fs\""));
    }

    @Test
    void leavesInputUnchangedWithoutMentions() {
        AtMentionExpander expander = new AtMentionExpander(
                (server, uri) -> new McpResourceReadResult("body", "text/plain"));

        assertEquals("普通输入", expander.expand("普通输入"));
    }

    @Test
    void insertsErrorBlockWhenReadFails() {
        AtMentionExpander expander = new AtMentionExpander((server, uri) -> {
            throw new IOException("boom");
        });

        String expanded = expander.expand("@fs:file://missing");

        assertTrue(expanded.contains("<resource_error"));
        assertTrue(expanded.contains("boom"));
    }

    private static int count(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
