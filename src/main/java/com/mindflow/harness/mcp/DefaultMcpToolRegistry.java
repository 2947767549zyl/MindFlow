package com.mindflow.harness.mcp;

import com.mindflow.harness.mcp.protocol.McpToolDescriptor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Service
public class DefaultMcpToolRegistry implements McpToolRegistry {
    private final Map<String, McpToolDescriptor> descriptors = new ConcurrentHashMap<>();
    private final Map<String, Function<String, String>> invokers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> namesByServer = new ConcurrentHashMap<>();

    @Override
    public void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        descriptors.put(descriptor.namespacedName(), descriptor);
        invokers.put(descriptor.namespacedName(), invoker);
        namesByServer
                .computeIfAbsent(descriptor.serverName(), ignored -> ConcurrentHashMap.newKeySet())
                .add(descriptor.namespacedName());
    }

    @Override
    public void unregisterMcpTool(String namespacedName) {
        McpToolDescriptor removed = descriptors.remove(namespacedName);
        invokers.remove(namespacedName);
        if (removed != null) {
            Set<String> names = namesByServer.get(removed.serverName());
            if (names != null) {
                names.remove(namespacedName);
            }
        }
    }

    @Override
    public void replaceMcpToolsForServer(String serverName, List<McpToolDescriptor> tools,
                                         Function<McpToolDescriptor, Function<String, String>> invokerFactory) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(invokerFactory, "invokerFactory");
        Set<String> previous = namesByServer.remove(serverName);
        if (previous != null) {
            for (String name : previous) {
                descriptors.remove(name);
                invokers.remove(name);
            }
        }
        for (McpToolDescriptor descriptor : tools) {
            registerMcpTool(descriptor, invokerFactory.apply(descriptor));
        }
    }

    @Override
    public boolean hasTool(String namespacedName) {
        return namespacedName != null && invokers.containsKey(namespacedName);
    }

    public List<McpToolDescriptor> getToolDefinitions() {
        return descriptors.values().stream()
                .sorted(Comparator.comparing(McpToolDescriptor::namespacedName))
                .toList();
    }

    public String executeTool(String namespacedName, String argumentsJson) {
        Function<String, String> invoker = invokers.get(namespacedName);
        if (invoker == null) {
            return "未知 MCP 工具: " + namespacedName;
        }
        try {
            return invoker.apply(argumentsJson);
        } catch (Exception e) {
            return "MCP 工具执行失败: " + e.getMessage();
        }
    }
}
