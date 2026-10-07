package com.mindflow.harness.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.harness.mcp.config.McpConfigLoader;
import com.mindflow.harness.mcp.config.McpExternalConfigSource;
import com.mindflow.harness.mcp.config.McpServerConfig;
import com.mindflow.harness.mcp.notifications.NotificationRouter;
import com.mindflow.harness.mcp.protocol.McpToolDescriptor;
import com.mindflow.harness.mcp.resources.McpResourceCache;
import com.mindflow.harness.mcp.resources.McpResourceContent;
import com.mindflow.harness.mcp.resources.McpResourceDescriptor;
import com.mindflow.harness.mcp.resources.McpResourceReadResult;
import com.mindflow.harness.mcp.resources.McpResourceTool;
import com.mindflow.harness.mcp.transport.McpTransport;
import com.mindflow.harness.mcp.transport.StdioTransport;
import com.mindflow.harness.mcp.transport.StreamableHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class McpServerManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);
    private static final Duration STARTUP_PROGRESS_INTERVAL = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final McpToolRegistry toolRegistry;
    private final Path projectDir;
    private final McpConfigLoader configLoader;
    private McpExternalConfigSource externalConfigSource;
    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();
    private final McpResourceCache resourceCache = new McpResourceCache();

    @Autowired
    public McpServerManager(McpConfigLoader configLoader,
                            ObjectProvider<McpToolRegistry> toolRegistryProvider,
                            ObjectMapper objectMapper,
                            @Value("${mindflow.mcp.project-dir:}") String configuredProjectDir) {
        this(objectMapper,
                toolRegistryProvider.getIfAvailable(DefaultMcpToolRegistry::new),
                configuredProjectDir == null || configuredProjectDir.isBlank()
                        ? Path.of(System.getProperty("user.dir"))
                        : Path.of(configuredProjectDir),
                configLoader);
    }

    public McpServerManager(McpToolRegistry toolRegistry, Path projectDir, McpConfigLoader configLoader) {
        this(new ObjectMapper(), toolRegistry, projectDir, configLoader);
    }

    private McpServerManager(ObjectMapper objectMapper, McpToolRegistry toolRegistry, Path projectDir,
                             McpConfigLoader configLoader) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.configLoader = configLoader;
    }

    /**
     * 由模块层（McpServerManagementService 构造时）主动注册，刻意不做 @Autowired：
     * 双方互相依赖会形成 mcpServerManager ↔ mcpServerManagementService 的循环依赖。
     */
    public void setExternalConfigSource(McpExternalConfigSource externalConfigSource) {
        this.externalConfigSource = externalConfigSource;
    }

    public synchronized void loadConfiguredServers() throws IOException {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>(configLoader.load());
        if (externalConfigSource != null) {
            // UI 管理的配置覆盖文件配置（数据库优先）
            configs.putAll(externalConfigSource.loadExternalConfigs());
        }
        servers.clear();
        configs.forEach((name, config) -> servers.put(name, new McpServer(name, config)));
    }

    public void startAll() {
        List<McpServer> targets = servers.values().stream()
                .filter(server -> !server.config().isDisabled())
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        // 用专属 daemon executor，避免 npx/uvx 冷启动期间占满 ForkJoinPool.commonPool 影响其他并发任务。
        AtomicInteger threadId = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(targets.size(), 8),
                r -> {
                    Thread t = new Thread(r, "mindflow-mcp-startup-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        Thread progressPrinter = startProgressPrinter(targets, STARTUP_PROGRESS_INTERVAL);
        try {
            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(server -> CompletableFuture.runAsync(() -> start(server), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            if (progressPrinter != null) {
                progressPrinter.interrupt();
            }
            executor.shutdown();
        }
    }

    private Thread startProgressPrinter(List<McpServer> targets, Duration interval) {
        if (targets.isEmpty()) {
            return null;
        }
        Map<String, Instant> startedAt = new ConcurrentHashMap<>();
        targets.forEach(server -> startedAt.put(server.name(), Instant.now()));
        Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    TimeUnit.MILLISECONDS.sleep(interval.toMillis());
                    List<McpServer> starting = targets.stream()
                            .filter(server -> server.status() == McpServerStatus.STARTING)
                            .sorted(Comparator.comparing(McpServer::name))
                            .toList();
                    if (starting.isEmpty()) {
                        continue;
                    }
                    for (McpServer server : starting) {
                        long waited = Duration.between(startedAt.get(server.name()), Instant.now()).toSeconds();
                        log.info("⏳ MCP server {} ({}) 启动中...（已等待 {}s）",
                                server.name(), server.transportName(), waited);
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "mindflow-mcp-startup-progress");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public synchronized String restart(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        unregisterTools(server);
        server.close();
        server.config().setDisabled(false);
        start(server);
        return server.status() == McpServerStatus.READY
                ? "✅ MCP server 已重启: " + name
                : "❌ MCP server 重启失败: " + name + " - " + server.errorMessage();
    }

    public synchronized String restartWithArgs(String name, List<String> args) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.config().setArgs(args);
        return restart(name);
    }

    /**
     * 运行时加载或替换单个 server：数据库是配置源，本方法把一条 DB 配置投影到运行时 map，
     * 先卸载同名旧实例（含进程与已注册工具）再按新配置启动。UI 增改配置后立即生效，无需重启。
     */
    public synchronized String putServer(String name, McpServerConfig config) {
        unload(name);
        McpServer server = new McpServer(name, config);
        servers.put(name, server);
        if (config.isDisabled()) {
            server.status(McpServerStatus.DISABLED);
            return "⏸️ MCP server 已登记（禁用）: " + name;
        }
        start(server);
        return server.status() == McpServerStatus.READY
                ? "✅ MCP server 已加载: " + name
                : "❌ MCP server 加载失败: " + name + " - " + server.errorMessage();
    }

    /** 运行时卸载单个 server：注销工具、关闭进程、清理资源缓存。UI 删除配置后立即生效，不留孤儿进程。 */
    public synchronized String removeServer(String name) {
        return unload(name) ? "🗑️ MCP server 已卸载: " + name : "未找到 MCP server: " + name;
    }

    private boolean unload(String name) {
        McpServer server = servers.remove(name);
        if (server == null) {
            return false;
        }
        unregisterTools(server);
        server.close();
        resourceCache.invalidateServer(name);
        return true;
    }

    public McpServer server(String name) {
        return servers.get(name);
    }

    public synchronized String disable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        unregisterTools(server);
        server.close();
        server.config().setDisabled(true);
        server.status(McpServerStatus.DISABLED);
        server.errorMessage(null);
        return "⏸️ MCP server 已禁用: " + name;
    }

    public synchronized String enable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.config().setDisabled(false);
        start(server);
        return server.status() == McpServerStatus.READY
                ? "▶️ MCP server 已启用: " + name
                : "❌ MCP server 启用失败: " + name + " - " + server.errorMessage();
    }

    public String logs(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        List<String> lines = server.logs();
        if (lines.isEmpty()) {
            return "📭 MCP server 暂无 stderr 日志: " + name;
        }
        return String.join(System.lineSeparator(), lines);
    }

    public Collection<McpServer> servers() {
        return servers.values().stream()
                .sorted(java.util.Comparator.comparing(McpServer::name))
                .toList();
    }

    public List<McpResourceDescriptor> resourceCandidates() {
        return resourceCache.all();
    }

    public String resourceIndexForPrompt() {
        List<McpResourceDescriptor> resources = resourceCache.all().stream()
                .sorted(Comparator.comparing(McpResourceDescriptor::serverName)
                        .thenComparing(McpResourceDescriptor::uri))
                .limit(200)
                .toList();
        if (resources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## MCP Resources 索引（仅 URI / 描述，不含正文）\n\n");
        sb.append("长上下文模式下可参考以下资源索引判断是否需要读取 resource；需要正文时再调用对应 MCP resource 工具或使用用户显式 @-mention。\n\n");
        for (McpResourceDescriptor resource : resources) {
            sb.append("- @").append(resource.serverName()).append(':').append(resource.uri());
            String displayName = resource.displayName();
            if (!displayName.equals(resource.uri())) {
                sb.append(" — ").append(displayName);
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("：").append(resource.description());
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" [").append(resource.mimeType()).append(']');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public String resources(String serverName) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return "未找到 MCP server: " + serverName;
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            return "MCP server 未就绪: " + serverName + " (" + server.status() + ")";
        }
        try {
            List<McpResourceDescriptor> resources = refreshResources(server);
            return McpClient.formatResources(resources);
        } catch (Exception e) {
            return "读取 MCP resources 失败: " + e.getMessage();
        }
    }

    public String prompts(String serverName) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return "未找到 MCP server: " + serverName;
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            return "MCP server 未就绪: " + serverName + " (" + server.status() + ")";
        }
        try {
            List<String> prompts = server.client().listPrompts();
            if (prompts.isEmpty()) {
                return "📭 该 MCP server 暂无 prompts: " + serverName;
            }
            StringBuilder sb = new StringBuilder("🧩 MCP prompts - ").append(serverName).append('\n');
            for (String prompt : prompts) {
                sb.append("- ").append(prompt).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "读取 MCP prompts 失败: " + e.getMessage();
        }
    }

    public McpResourceReadResult readResourceForMention(String serverName, String uri) throws IOException {
        McpServer server = servers.get(serverName);
        if (server == null) {
            throw new IOException("未找到 MCP server: " + serverName);
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            throw new IOException("MCP server 未就绪: " + serverName + " (" + server.status() + ")");
        }
        try {
            if (resourceCache.isServerStale(serverName)) {
                refreshResources(server);
            }
            List<McpResourceContent> contents = server.client().readResource(uri);
            resourceCache.markResourceFresh(serverName, uri);
            return McpResourceReadResult.from(contents);
        } catch (Exception e) {
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    private void start(McpServer server) {
        unregisterTools(server);
        server.close();
        if (server.config().isDisabled()) {
            server.status(McpServerStatus.DISABLED);
            return;
        }
        server.status(McpServerStatus.STARTING);
        server.errorMessage(null);
        try {
            // 在单 server 启动路径里展开 ${VAR} 与校验 transport，
            // 单个失败仅标 ERROR，不会阻塞其他 server。
            configLoader.prepare(server.config());
            McpTransport transport = createTransport(server.config());
            McpClient client = new McpClient(server.name(), transport);
            client.initialize();
            registerNotificationHandlers(server, client);
            List<McpToolDescriptor> tools = buildToolList(server, client);
            replaceTools(server, client, tools);
            server.client(client);
            server.tools(tools);
            server.markStarted();
            server.status(McpServerStatus.READY);
        } catch (Exception e) {
            server.close();
            server.errorMessage(e.getMessage());
            server.status(McpServerStatus.ERROR);
        }
    }

    private List<McpToolDescriptor> buildToolList(McpServer server, McpClient client) throws IOException {
        List<McpToolDescriptor> tools = new ArrayList<>(client.listTools());
        if (client.supportsResources()) {
            List<McpResourceDescriptor> resources = client.listResources();
            resourceCache.put(server.name(), resources);
            tools.addAll(McpResourceTool.descriptors(server.name()));
        }
        validateNoDuplicateTools(server.name(), tools);
        return tools;
    }

    private void replaceTools(McpServer server, McpClient client, List<McpToolDescriptor> tools) {
        toolRegistry.replaceMcpToolsForServer(server.name(), tools,
                descriptor -> isResourceVirtualTool(descriptor)
                        ? McpResourceTool.invoker(objectMapper, client, descriptor)
                        : args -> invokeMcpTool(client, descriptor, args));
    }

    private boolean isResourceVirtualTool(McpToolDescriptor descriptor) {
        return McpResourceTool.LIST_RESOURCES.equals(descriptor.name())
                || McpResourceTool.READ_RESOURCE.equals(descriptor.name());
    }

    private void registerNotificationHandlers(McpServer server, McpClient client) {
        NotificationRouter router = new NotificationRouter();
        router.on("notifications/tools/list_changed", ignored -> {
            try {
                List<McpToolDescriptor> tools = buildToolList(server, client);
                replaceTools(server, client, tools);
                server.tools(tools);
            } catch (Exception e) {
                server.errorMessage("tools/list_changed 处理失败: " + e.getMessage());
            }
        });
        router.on("notifications/resources/list_changed", ignored -> resourceCache.invalidateServer(server.name()));
        router.on("notifications/resources/updated", params -> {
            String uri = params.path("uri").asText("");
            if (!uri.isBlank()) {
                resourceCache.invalidateResource(server.name(), uri);
            }
        });
        client.onNotification(router);
    }

    private List<McpResourceDescriptor> refreshResources(McpServer server) throws IOException {
        List<McpResourceDescriptor> resources = server.client().listResources();
        resources = resources.stream()
                .sorted(Comparator.comparing(McpResourceDescriptor::uri))
                .toList();
        resourceCache.put(server.name(), resources);
        return resources;
    }

    /**
     * MCP 工具执行入口：把 LLM 给的 JSON 参数透传给 server 的 tools/call，并把异常转成可读字符串。
     * 提取成独立方法是为了让 server 维度的错误信息（serverName/toolName）在堆栈和日志里清晰可见。
     */
    private static String invokeMcpTool(McpClient client, McpToolDescriptor descriptor, String argumentsJson) {
        try {
            return client.callTool(descriptor.name(), argumentsJson);
        } catch (Exception e) {
            return "MCP 工具调用失败 (" + descriptor.serverName() + "/" + descriptor.name() + "): " + e.getMessage();
        }
    }

    private McpTransport createTransport(McpServerConfig config) throws IOException {
        if (config.isHttp()) {
            return new StreamableHttpTransport(config.getUrl(), config.getHeaders());
        }
        return new StdioTransport(config.getCommand(), config.getArgs(), config.getEnv(), projectDir);
    }

    private void validateNoDuplicateTools(String serverName, List<McpToolDescriptor> tools) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (McpToolDescriptor tool : tools) {
            counts.merge(tool.name(), 1, Integer::sum);
        }
        List<String> duplicates = new ArrayList<>();
        counts.forEach((name, count) -> {
            if (count > 1) duplicates.add(name);
        });
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("MCP server " + serverName + " 返回重复工具名: " + duplicates);
        }
    }

    private void unregisterTools(McpServer server) {
        for (McpToolDescriptor tool : server.tools()) {
            toolRegistry.unregisterMcpTool(tool.namespacedName());
        }
        server.tools(List.of());
    }

    @Override
    public void close() {
        for (McpServer server : servers.values()) {
            unregisterTools(server);
            server.close();
        }
    }
}
