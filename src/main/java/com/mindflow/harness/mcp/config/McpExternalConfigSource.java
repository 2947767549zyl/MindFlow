package com.mindflow.harness.mcp.config;

import java.util.Map;

/**
 * 外部（数据库）MCP 配置来源。让 harness 层不依赖 module 层：
 * McpServerManager 通过该接口拿到 UI 管理的 server 配置，与文件配置合并（外部优先）。
 */
public interface McpExternalConfigSource {

    Map<String, McpServerConfig> loadExternalConfigs();
}
