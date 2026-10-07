package com.mindflow.harness.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.harness.mcp.protocol.McpToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 DefaultMcpToolRegistry 的 MCP 工具注册 / 反注册 / 调用路由。
 *
 * MCP 工具由 registry 的 mcp 子表持有，executeTool 检测到注册名后
 * 路由到注册时提供的 invoker 函数。
 */
class McpToolRegistrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void registersAndRoutesMcpToolToInvoker() throws Exception {
        DefaultMcpToolRegistry registry = new DefaultMcpToolRegistry();
        McpToolDescriptor descriptor = sampleDescriptor();
        registry.registerMcpTool(descriptor, args -> "echo:" + args);

        assertTrue(registry.hasTool("mcp__demo__echo"));
        assertTrue(registry.getToolDefinitions().stream().anyMatch(t -> t.namespacedName().equals("mcp__demo__echo")));
        assertEquals("echo:{\"text\":\"hi\"}", registry.executeTool("mcp__demo__echo", "{\"text\":\"hi\"}"));
    }

    @Test
    void unregisterRemovesMcpToolFromBothViews() throws Exception {
        DefaultMcpToolRegistry registry = new DefaultMcpToolRegistry();
        McpToolDescriptor descriptor = sampleDescriptor();
        registry.registerMcpTool(descriptor, args -> "echo:" + args);
        registry.unregisterMcpTool("mcp__demo__echo");

        assertFalse(registry.hasTool("mcp__demo__echo"));
        assertTrue(registry.getToolDefinitions().stream().noneMatch(t -> t.namespacedName().equals("mcp__demo__echo")));
    }

    @Test
    void invokerExceptionsAreReportedAsToolErrorWithoutCrashingRegistry() throws Exception {
        DefaultMcpToolRegistry registry = new DefaultMcpToolRegistry();
        registry.registerMcpTool(sampleDescriptor(), args -> {
            throw new RuntimeException("upstream broke");
        });

        String result = registry.executeTool("mcp__demo__echo", "{}");
        assertTrue(result.contains("upstream broke"), "结果应包含 invoker 抛出的错误信息: " + result);
    }

    @Test
    void registerMcpToolRejectsNullArgs() throws Exception {
        DefaultMcpToolRegistry registry = new DefaultMcpToolRegistry();
        assertThrows(NullPointerException.class,
                () -> registry.registerMcpTool(null, args -> "x"));
        assertThrows(NullPointerException.class,
                () -> registry.registerMcpTool(sampleDescriptor(), null));
    }

    @Test
    void replaceMcpToolsForServerAtomicallyReplacesOnlyThatServer() throws Exception {
        DefaultMcpToolRegistry registry = new DefaultMcpToolRegistry();
        registry.registerMcpTool(sampleDescriptor("demo", "old"), args -> "old");
        registry.registerMcpTool(sampleDescriptor("other", "keep"), args -> "keep");

        registry.replaceMcpToolsForServer("demo",
                List.of(sampleDescriptor("demo", "new")),
                descriptor -> args -> "new:" + descriptor.name());

        assertFalse(registry.hasTool("mcp__demo__old"));
        assertTrue(registry.hasTool("mcp__demo__new"));
        assertTrue(registry.hasTool("mcp__other__keep"));
        assertEquals("new:new", registry.executeTool("mcp__demo__new", "{}"));
    }

    private static McpToolDescriptor sampleDescriptor() throws Exception {
        return sampleDescriptor("demo", "echo");
    }

    private static McpToolDescriptor sampleDescriptor(String server, String name) throws Exception {
        return new McpToolDescriptor(
                server,
                name,
                "mcp__" + server + "__" + name,
                "Echo input",
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
        );
    }
}
