package com.mindflow.module.mcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.harness.mcp.McpServer;
import com.mindflow.harness.mcp.McpServerManager;
import com.mindflow.harness.mcp.config.McpExternalConfigSource;
import com.mindflow.harness.mcp.config.McpServerConfig;
import com.mindflow.harness.policy.AuditLogService;
import com.mindflow.module.mcp.entity.McpServerConfigEntity;
import com.mindflow.module.mcp.repository.McpServerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server 配置管理与运行时操作。
 * 同时实现 {@link McpExternalConfigSource}，让 {@link McpServerManager} 在加载配置时把
 * UI 管理的 server 与文件配置合并（数据库优先）。
 */
@Service
public class McpServerManagementService implements McpExternalConfigSource {

    private static final Logger logger = LoggerFactory.getLogger(McpServerManagementService.class);

    private final McpServerConfigRepository repository;
    private final McpServerConfigGuard guard;
    private final McpServerManager serverManager;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public McpServerManagementService(McpServerConfigRepository repository,
                                      McpServerConfigGuard guard,
                                      McpServerManager serverManager,
                                      AuditLogService auditLogService,
                                      ObjectMapper objectMapper) {
        this.repository = repository;
        this.guard = guard;
        this.serverManager = serverManager;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        serverManager.setExternalConfigSource(this);
    }

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpServerConfigEntity entity : repository.findAllByOrderByNameAsc()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entity.getName());
            item.put("transportType", entity.getTransportType());
            item.put("command", entity.getCommand());
            item.put("args", parseList(entity.getArgsJson()));
            item.put("url", entity.getUrl());
            item.put("headers", parseMap(entity.getHeadersJson()));
            item.put("env", parseMap(entity.getEnvJson()));
            item.put("enabled", entity.isEnabled());
            item.put("createdBy", entity.getCreatedBy());
            item.put("updatedAt", entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());

            McpServer runtime = serverManager.server(entity.getName());
            item.put("runtimeStatus", runtime == null ? "NOT_STARTED" : runtime.status().name());
            result.add(item);
        }
        return result;
    }

    public McpServerConfigEntity create(Map<String, Object> body, String operator) {
        String name = stringValue(body.get("name"));
        String transportType = stringValue(body.get("transportType"));
        String command = stringValue(body.get("command"));
        List<String> args = listValue(body.get("args"));
        String url = stringValue(body.get("url"));

        guard.validate(name, transportType, command, args, url);
        if (repository.existsByName(name)) {
            throw new IllegalArgumentException("同名 MCP server 已存在: " + name);
        }

        McpServerConfigEntity entity = new McpServerConfigEntity();
        entity.setName(name);
        entity.setTransportType(transportType.toLowerCase());
        entity.setCommand(command);
        entity.setArgsJson(writeJson(args));
        entity.setUrl(url);
        entity.setHeadersJson(writeJson(mapValue(body.get("headers"))));
        entity.setEnvJson(writeJson(mapValue(body.get("env"))));
        entity.setEnabled(true);
        entity.setCreatedBy(operator);
        McpServerConfigEntity saved = repository.save(entity);

        audit("create", name, operator, true, null);
        String runtime = serverManager.putServer(name, toHarnessConfig(saved));
        logger.info("MCP server 配置已创建并热加载: name={}, transport={}, operator={}, runtime={}",
                name, transportType, operator, runtime);
        return saved;
    }

    public McpServerConfigEntity update(String name, Map<String, Object> body, String operator) {
        McpServerConfigEntity entity = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("MCP server 不存在: " + name));

        String transportType = body.containsKey("transportType")
                ? stringValue(body.get("transportType")) : entity.getTransportType();
        String command = body.containsKey("command") ? stringValue(body.get("command")) : entity.getCommand();
        List<String> args = body.containsKey("args") ? listValue(body.get("args")) : parseList(entity.getArgsJson());
        String url = body.containsKey("url") ? stringValue(body.get("url")) : entity.getUrl();

        guard.validate(name, transportType, command, args, url);

        entity.setTransportType(transportType.toLowerCase());
        entity.setCommand(command);
        entity.setArgsJson(writeJson(args));
        entity.setUrl(url);
        if (body.containsKey("headers")) {
            entity.setHeadersJson(writeJson(mapValue(body.get("headers"))));
        }
        if (body.containsKey("env")) {
            entity.setEnvJson(writeJson(mapValue(body.get("env"))));
        }
        if (body.containsKey("enabled")) {
            entity.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        }
        McpServerConfigEntity saved = repository.save(entity);

        audit("update", name, operator, true, null);
        String runtime = serverManager.putServer(name, toHarnessConfig(saved));
        logger.info("MCP server 配置已更新并热重载: name={}, operator={}, runtime={}", name, operator, runtime);
        return saved;
    }

    public boolean delete(String name, String operator) {
        McpServerConfigEntity entity = repository.findByName(name).orElse(null);
        if (entity == null) {
            return false;
        }
        repository.delete(entity);
        serverManager.removeServer(name);
        audit("delete", name, operator, true, null);
        logger.info("MCP server 配置已删除: name={}, operator={}", name, operator);
        return true;
    }

    public String setEnabled(String name, boolean enabled, String operator) {
        McpServerConfigEntity entity = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("MCP server 不存在: " + name));
        entity.setEnabled(enabled);
        repository.save(entity);
        String runtime = serverManager.server(name) == null
                ? serverManager.putServer(name, toHarnessConfig(entity))
                : enabled ? serverManager.enable(name) : serverManager.disable(name);
        audit(enabled ? "enable" : "disable", name, operator, true, null);
        return runtime;
    }

    public String restart(String name, String operator) {
        McpServerConfigEntity entity = repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("MCP server 不存在: " + name));
        String result = serverManager.server(name) == null
                ? serverManager.putServer(name, toHarnessConfig(entity))
                : serverManager.restart(name);
        audit("restart", name, operator, true, null);
        return result;
    }

    public String logs(String name) {
        return serverManager.logs(name);
    }

    @Override
    public Map<String, McpServerConfig> loadExternalConfigs() {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        try {
            for (McpServerConfigEntity entity : repository.findByEnabledTrue()) {
                configs.put(entity.getName(), toHarnessConfig(entity));
            }
        } catch (Exception e) {
            logger.warn("读取数据库 MCP 配置失败，仅使用文件配置: {}", e.getMessage());
        }
        return configs;
    }

    private McpServerConfig toHarnessConfig(McpServerConfigEntity entity) {
        McpServerConfig config = new McpServerConfig();
        config.setCommand(entity.getCommand());
        config.setArgs(parseList(entity.getArgsJson()));
        config.setUrl(entity.getUrl());
        config.setHeaders(parseMap(entity.getHeadersJson()));
        config.setEnv(parseMap(entity.getEnvJson()));
        config.setDisabled(!entity.isEnabled());
        return config;
    }

    private void audit(String action, String name, String operator, boolean allowed, String reason) {
        try {
            auditLogService.record("mcp_config:" + action, "{\"server\":\"" + name + "\"}",
                    allowed ? AuditLogService.OUTCOME_ALLOW : AuditLogService.OUTCOME_DENY,
                    reason, AuditLogService.APPROVER_NONE, 0, operator, null);
        } catch (Exception e) {
            logger.warn("MCP 配置变更审计写入失败: {}", e.getMessage());
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private List<String> listValue(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null && item != null) {
                    result.put(String.valueOf(key), String.valueOf(item));
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, String> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
