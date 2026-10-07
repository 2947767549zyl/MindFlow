package com.mindflow.module.chat.controller;

import com.mindflow.common.exception.CustomException;
import com.mindflow.harness.mcp.DefaultMcpToolRegistry;
import com.mindflow.harness.mcp.McpServer;
import com.mindflow.harness.mcp.McpServerManager;
import com.mindflow.harness.mcp.protocol.McpToolDescriptor;
import com.mindflow.module.chat.agent.AgentToolRegistry;
import com.mindflow.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具目录（只读元数据）：
 * - builtin：内置受信工具（只读/受控，免 HITL）
 * - mcp：通过 MCP 引入的外置工具（不受控，调用一律强制 HITL 审批 + 审计）
 */
@RestController
@RequestMapping("/api/v1/agent/tools")
public class AgentToolController {

    private final AgentToolRegistry agentToolRegistry;
    private final DefaultMcpToolRegistry mcpToolRegistry;
    private final McpServerManager mcpServerManager;
    private final JwtUtils jwtUtils;

    public AgentToolController(AgentToolRegistry agentToolRegistry,
                               DefaultMcpToolRegistry mcpToolRegistry,
                               McpServerManager mcpServerManager,
                               JwtUtils jwtUtils) {
        this.agentToolRegistry = agentToolRegistry;
        this.mcpToolRegistry = mcpToolRegistry;
        this.mcpServerManager = mcpServerManager;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String token) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        if (userId == null || userId.isBlank()) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }

        List<Map<String, Object>> builtin = new ArrayList<>();
        for (AgentToolRegistry.AgentTool tool : agentToolRegistry.builtinTools()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.put("parameters", tool.parameters());
            builtin.add(item);
        }

        List<Map<String, Object>> mcpTools = new ArrayList<>();
        for (McpToolDescriptor descriptor : mcpToolRegistry.getToolDefinitions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("serverName", descriptor.serverName());
            item.put("name", descriptor.name());
            item.put("namespacedName", descriptor.namespacedName());
            item.put("description", descriptor.description());
            item.put("inputSchema", descriptor.inputSchema());
            mcpTools.add(item);
        }

        List<Map<String, Object>> servers = new ArrayList<>();
        for (McpServer server : mcpServerManager.servers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", server.name());
            item.put("transport", server.transportName());
            item.put("status", server.status() == null ? "UNKNOWN" : server.status().name());
            servers.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("builtin", builtin);
        data.put("builtinCount", builtin.size());
        data.put("mcp", mcpTools);
        data.put("mcpCount", mcpTools.size());
        data.put("mcpServers", servers);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "success");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}
