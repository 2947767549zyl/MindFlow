package com.mindflow.harness.mcp.mention;

import com.mindflow.harness.mcp.resources.McpResourceReadResult;

import java.io.IOException;

@FunctionalInterface
public interface McpResourceReader {
    McpResourceReadResult read(String serverName, String uri) throws IOException;
}
