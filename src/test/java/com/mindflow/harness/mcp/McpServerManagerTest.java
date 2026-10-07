package com.mindflow.harness.mcp;

import com.mindflow.harness.mcp.config.McpConfigLoader;
import com.mindflow.harness.mcp.config.McpServerConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通过 JDK 内置 HttpServer 模拟 Streamable HTTP MCP server 来端到端验证 McpServerManager 的启停流程。
 * 不测真实 stdio 子进程（已在 StdioTransportTest 单独覆盖）。
 */
class McpServerManagerTest {

    private HttpServer webServer;
    private final ConcurrentLinkedQueue<MockResponse> responses = new ConcurrentLinkedQueue<>();
    private DefaultMcpToolRegistry registry;
    private McpServerManager manager;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        webServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        webServer.createContext("/mcp", this::handle);
        webServer.start();
        registry = new DefaultMcpToolRegistry();
        // 用空 config loader 占位，单独把 server 直接放进 manager（避开真实文件读取）
        manager = new McpServerManager(registry, tempDir,
                new McpConfigLoader(tempDir.resolve("user.json"), tempDir.resolve("project.json"), tempDir));
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.close();
        if (webServer != null) webServer.stop(0);
    }

    private record MockResponse(int code, Map<String, String> headers, String body) {
        static MockResponse json(Map<String, String> extraHeaders, String body) {
            Map<String, String> headers = new HashMap<>(extraHeaders);
            headers.put("Content-Type", "application/json");
            return new MockResponse(200, headers, body);
        }

        static MockResponse status(int code) {
            return new MockResponse(code, Map.of(), "");
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            MockResponse next = responses.poll();
            int code = next == null ? 500 : next.code();
            byte[] body = next == null || next.body() == null
                    ? new byte[0]
                    : next.body().getBytes(StandardCharsets.UTF_8);
            if (next != null) {
                next.headers().forEach(exchange.getResponseHeaders()::set);
            }
            if (body.length == 0) {
                exchange.sendResponseHeaders(code, -1);
            } else {
                exchange.sendResponseHeaders(code, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }

    @Test
    void startAllStartsHttpServerAndRegistersTools() throws Exception {
        enqueueInitialize();
        enqueueToolsList(toolJson("echo", "Echo back text"));

        loadServersFromMap(Map.of("demo", httpConfig()));
        manager.startAll();

        McpServer server = manager.servers().iterator().next();
        assertEquals(McpServerStatus.READY, server.status(), "状态应为 READY，错误: " + server.errorMessage());
        assertEquals(1, server.tools().size());
        assertTrue(registry.hasTool("mcp__demo__echo"));
    }

    @Test
    void resourcesCapabilityRegistersVirtualResourceTools() throws Exception {
        enqueueInitialize("{\"resources\":{\"listChanged\":true},\"prompts\":{}}");
        enqueueToolsList(toolJson("echo", "Echo back text"));
        enqueueResourcesList();

        loadServersFromMap(Map.of("demo", httpConfig()));
        manager.startAll();

        assertTrue(registry.hasTool("mcp__demo__echo"));
        assertTrue(registry.hasTool("mcp__demo__list_resources"));
        assertTrue(registry.hasTool("mcp__demo__read_resource"));
        assertTrue(manager.resourceCandidates().stream().anyMatch(r -> r.uri().equals("file://README.md")));
        assertTrue(manager.resourceIndexForPrompt().contains("@demo:file://README.md"));

        enqueuePromptsList();
        assertTrue(manager.prompts("demo").contains("Review (review)"));
    }

    @Test
    void singleServerFailureDoesNotBlockOthers() throws Exception {
        // 一个 OK 的 server + 一个引用未设置 ${VAR} 的 server
        enqueueInitialize();
        enqueueToolsList(toolJson("ok", "ok tool"));

        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        configs.put("good", httpConfig());
        McpServerConfig bad = new McpServerConfig();
        bad.setUrl("https://example.com/${UNSET_DEMO_VAR_FOR_TEST}");
        configs.put("bad", bad);
        loadServersFromMap(configs);

        manager.startAll();

        Map<String, McpServer> byName = new HashMap<>();
        manager.servers().forEach(s -> byName.put(s.name(), s));
        assertEquals(McpServerStatus.READY, byName.get("good").status(),
                "good 应正常启动，不被 bad 阻塞");
        assertEquals(McpServerStatus.ERROR, byName.get("bad").status(),
                "bad 应标 ERROR");
        assertNotNull(byName.get("bad").errorMessage());
        assertTrue(registry.hasTool("mcp__good__ok"));
    }

    @Test
    void disableRemovesToolsFromRegistry() throws Exception {
        enqueueInitialize();
        enqueueToolsList(toolJson("echo", "Echo"));

        loadServersFromMap(Map.of("demo", httpConfig()));
        manager.startAll();
        assertTrue(registry.hasTool("mcp__demo__echo"));

        String result = manager.disable("demo");
        assertTrue(result.contains("已禁用"));
        assertFalse(registry.hasTool("mcp__demo__echo"),
                "disable 后 tool registry 应不再持有该工具");
        McpServer server = manager.servers().iterator().next();
        assertEquals(McpServerStatus.DISABLED, server.status());
    }

    @Test
    void restartReregistersToolsAfterFailure() throws Exception {
        // 第一次启动：失败（401）
        responses.add(MockResponse.status(401));

        loadServersFromMap(Map.of("demo", httpConfig()));
        manager.startAll();

        McpServer server = manager.servers().iterator().next();
        assertEquals(McpServerStatus.ERROR, server.status());
        assertFalse(registry.hasTool("mcp__demo__echo"));

        // 第二次启动：成功
        enqueueInitialize();
        enqueueToolsList(toolJson("echo", "Echo"));

        String result = manager.restart("demo");
        assertEquals(McpServerStatus.READY, server.status(), "重启后应 READY: " + result);
        assertTrue(registry.hasTool("mcp__demo__echo"));
    }

    @Test
    void restartWithArgsUpdatesServerConfig() {
        McpServerConfig config = new McpServerConfig();
        config.setCommand("definitely-missing-mindflow-test-command");
        config.setArgs(List.of("old"));
        loadServersFromMap(Map.of("demo", config));

        String result = manager.restartWithArgs("demo", List.of("new", "args"));

        McpServer server = manager.server("demo");
        assertEquals(List.of("new", "args"), server.config().getArgs());
        assertTrue(result.contains("重启失败"));
    }

    @Test
    void unknownServerOperationsReturnFriendlyError() {
        loadServersFromMap(Map.of());
        assertTrue(manager.disable("missing").contains("未找到"));
        assertTrue(manager.enable("missing").contains("未找到"));
        assertTrue(manager.restart("missing").contains("未找到"));
        assertTrue(manager.logs("missing").contains("未找到"));
        assertTrue(manager.removeServer("missing").contains("未找到"));
    }

    @Test
    void putServerLoadsNewServerImmediately() throws Exception {
        enqueueInitialize();
        enqueueToolsList(toolJson("echo", "Echo"));

        String result = manager.putServer("hot", httpConfig());

        McpServer server = manager.server("hot");
        assertNotNull(server, "putServer 后运行时 map 应立即有条目");
        assertEquals(McpServerStatus.READY, server.status(), "应加载成功: " + result + " / " + server.errorMessage());
        assertTrue(registry.hasTool("mcp__hot__echo"), "工具应已注册，无需重启");
    }

    @Test
    void putServerWithDisabledConfigRegistersWithoutStarting() {
        String result = manager.putServer("off", disabledHttpConfig());

        McpServer server = manager.server("off");
        assertNotNull(server, "禁用配置也应登记到运行时 map，便于后续 enable");
        assertEquals(McpServerStatus.DISABLED, server.status());
        assertTrue(result.contains("禁用"), result);
        assertFalse(registry.hasTool("mcp__off__echo"));
    }

    @Test
    void putServerReplacesExistingServerAndSwapsTools() throws Exception {
        enqueueInitialize();
        enqueueToolsList(toolJson("echo", "Echo"));
        manager.putServer("demo", httpConfig());
        assertTrue(registry.hasTool("mcp__demo__echo"));

        // 占位响应：替换时 close() 会对 mock 发 DELETE 并抢走队首，不补位会串位
        responses.add(MockResponse.status(200));
        enqueueInitialize();
        enqueueToolsList(toolJson("echo2", "Echo two"));

        String result = manager.putServer("demo", httpConfig());

        assertEquals(McpServerStatus.READY, manager.server("demo").status(), result);
        assertTrue(registry.hasTool("mcp__demo__echo2"), "新工具应注册");
        assertFalse(registry.hasTool("mcp__demo__echo"), "旧工具应被移除");
        assertEquals(1, manager.servers().size(), "同名替换不应产生重复条目");
    }

    @Test
    void removeServerUnloadsAndUnregistersTools() throws Exception {
        enqueueInitialize();
        enqueueToolsList(toolJson("echo", "Echo"));
        manager.putServer("demo", httpConfig());
        assertTrue(registry.hasTool("mcp__demo__echo"));

        responses.add(MockResponse.status(200));
        String result = manager.removeServer("demo");

        assertTrue(result.contains("已卸载"), result);
        assertNull(manager.server("demo"), "运行时 map 应已移除");
        assertFalse(registry.hasTool("mcp__demo__echo"), "工具应已注销，避免残留");
        assertTrue(manager.servers().isEmpty());
    }

    // ---- helpers ----

    private void enqueueInitialize() {
        enqueueInitialize(null);
    }

    private void enqueueInitialize(String capabilitiesJson) {
        String capabilities = capabilitiesJson == null ? "" : ",\"capabilities\":" + capabilitiesJson;
        // initialize 请求响应
        responses.add(MockResponse.json(
                Map.of("Mcp-Session-Id", "session-test"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"" + capabilities + "}}"));
        // initialized 通知（无 id），server 仍要返回 200，body 任意
        responses.add(MockResponse.json(Map.of(), ""));
    }

    private void enqueueToolsList(String toolJson) {
        responses.add(MockResponse.json(Map.of(),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[" + toolJson + "]}}"));
    }

    private void enqueueResourcesList() {
        responses.add(MockResponse.json(Map.of(), """
                {"jsonrpc":"2.0","id":3,"result":{"resources":[
                  {"uri":"file://README.md","name":"README.md","description":"docs","mimeType":"text/markdown"}
                ]}}
                """));
    }

    private void enqueuePromptsList() {
        responses.add(MockResponse.json(Map.of(), """
                {"jsonrpc":"2.0","id":4,"result":{"prompts":[
                  {"name":"review","title":"Review","description":"Review code"}
                ]}}
                """));
    }

    private static String toolJson(String name, String description) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + description + "\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}";
    }

    private McpServerConfig httpConfig() {
        McpServerConfig config = new McpServerConfig();
        config.setUrl("http://localhost:" + webServer.getAddress().getPort() + "/mcp");
        return config;
    }

    private McpServerConfig disabledHttpConfig() {
        McpServerConfig config = httpConfig();
        config.setDisabled(true);
        return config;
    }

    private void loadServersFromMap(Map<String, McpServerConfig> configs) {
        // 这里复用 loadConfiguredServers 的内部行为：把 configs 写入 manager 的 servers map。
        // 简化为：直接通过 reflection 写入 servers map。
        try {
            java.lang.reflect.Field f = McpServerManager.class.getDeclaredField("servers");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, McpServer> map = (Map<String, McpServer>) f.get(manager);
            map.clear();
            configs.forEach((name, cfg) -> map.put(name, new McpServer(name, cfg)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
