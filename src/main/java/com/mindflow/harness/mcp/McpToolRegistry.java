package com.mindflow.harness.mcp;

import com.mindflow.harness.mcp.protocol.McpToolDescriptor;

import java.util.List;
import java.util.function.Function;

public interface McpToolRegistry {
    void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker);

    void unregisterMcpTool(String namespacedName);

    void replaceMcpToolsForServer(String serverName, List<McpToolDescriptor> tools,
                                  Function<McpToolDescriptor, Function<String, String>> invokerFactory);

    boolean hasTool(String namespacedName);
}
