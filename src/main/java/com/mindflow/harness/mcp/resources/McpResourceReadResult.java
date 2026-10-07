package com.mindflow.harness.mcp.resources;

import java.util.List;

public record McpResourceReadResult(String content, String mimeType) {
    public static McpResourceReadResult from(List<McpResourceContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return new McpResourceReadResult("", "text/plain");
        }
        StringBuilder text = new StringBuilder();
        String firstMimeType = null;
        for (McpResourceContent content : contents) {
            if (firstMimeType == null || firstMimeType.isBlank()) {
                firstMimeType = content.mimeType();
            }
            if (content.isText()) {
                text.append(content.text());
            } else {
                text.append("[binary resource blob omitted, base64 length=")
                        .append(content.blob() == null ? 0 : content.blob().length())
                        .append(']');
            }
            text.append(System.lineSeparator());
        }
        return new McpResourceReadResult(text.toString().trim(), firstMimeType);
    }
}
