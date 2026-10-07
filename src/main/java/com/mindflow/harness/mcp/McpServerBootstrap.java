package com.mindflow.harness.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP server 启动引导：应用就绪后异步加载 mcp.json 配置并启动全部 server。
 * 无配置文件时为 no-op；启动失败仅告警，绝不阻断应用启动。
 */
@Service
public class McpServerBootstrap implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(McpServerBootstrap.class);

    private final McpServerManager serverManager;

    @Value("${mindflow.mcp.enabled:true}")
    private boolean enabled;

    private final AtomicBoolean started = new AtomicBoolean(false);

    public McpServerBootstrap(McpServerManager serverManager) {
        this.serverManager = serverManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || !started.compareAndSet(false, true)) {
            return;
        }
        Thread starter = new Thread(() -> {
            try {
                serverManager.loadConfiguredServers();
                serverManager.startAll();
            } catch (Exception e) {
                logger.warn("MCP server 启动失败（不影响应用运行）: {}", e.getMessage());
            }
        }, "mindflow-mcp-bootstrap");
        starter.setDaemon(true);
        starter.start();
    }
}
