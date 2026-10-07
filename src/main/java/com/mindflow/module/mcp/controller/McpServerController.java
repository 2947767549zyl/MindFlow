package com.mindflow.module.mcp.controller;

import com.mindflow.common.exception.CustomException;
import com.mindflow.module.auth.entity.User;
import com.mindflow.module.auth.repository.UserRepository;
import com.mindflow.module.mcp.entity.McpServerConfigEntity;
import com.mindflow.module.mcp.service.McpServerManagementService;
import com.mindflow.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP server 自定义配置接口。
 * 安全约束：仅 ADMIN 可读写；所有变更记审计；命令白名单与 SSRF 校验在 service 层。
 */
@RestController
@RequestMapping("/api/v1/mcp/servers")
public class McpServerController {

    private final McpServerManagementService managementService;
    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;

    public McpServerController(McpServerManagementService managementService,
                               JwtUtils jwtUtils,
                               UserRepository userRepository) {
        this.managementService = managementService;
        this.jwtUtils = jwtUtils;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String token) {
        requireAdmin(token);
        return ok("success", managementService.listAll());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader("Authorization") String token,
                                    @RequestBody Map<String, Object> body) {
        String operator = requireAdmin(token);
        McpServerConfigEntity created = managementService.create(body, operator);
        return ok("已创建", Map.of("name", created.getName()));
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> update(@RequestHeader("Authorization") String token,
                                    @PathVariable String name,
                                    @RequestBody Map<String, Object> body) {
        String operator = requireAdmin(token);
        McpServerConfigEntity updated = managementService.update(name, body, operator);
        return ok("已更新", Map.of("name", updated.getName()));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@RequestHeader("Authorization") String token,
                                    @PathVariable String name) {
        String operator = requireAdmin(token);
        boolean deleted = managementService.delete(name, operator);
        return ok(deleted ? "已删除" : "MCP server 不存在", Map.of("deleted", deleted));
    }

    @PostMapping("/{name}/enable")
    public ResponseEntity<?> enable(@RequestHeader("Authorization") String token,
                                    @PathVariable String name) {
        String operator = requireAdmin(token);
        String runtimeMessage = managementService.setEnabled(name, true, operator);
        return ok("已启用", Map.of("runtime", runtimeMessage));
    }

    @PostMapping("/{name}/disable")
    public ResponseEntity<?> disable(@RequestHeader("Authorization") String token,
                                     @PathVariable String name) {
        String operator = requireAdmin(token);
        String runtimeMessage = managementService.setEnabled(name, false, operator);
        return ok("已禁用", Map.of("runtime", runtimeMessage));
    }

    @PostMapping("/{name}/restart")
    public ResponseEntity<?> restart(@RequestHeader("Authorization") String token,
                                     @PathVariable String name) {
        String operator = requireAdmin(token);
        String runtimeMessage = managementService.restart(name, operator);
        return ok("已重启", Map.of("runtime", runtimeMessage));
    }

    @GetMapping("/{name}/logs")
    public ResponseEntity<?> logs(@RequestHeader("Authorization") String token,
                                  @PathVariable String name) {
        requireAdmin(token);
        return ok("success", Map.of("logs", managementService.logs(name)));
    }

    private String requireAdmin(String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (username == null || username.isBlank()) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.UNAUTHORIZED));
        if (user.getRole() != User.Role.ADMIN) {
            throw new CustomException("仅管理员可管理 MCP server 配置", HttpStatus.FORBIDDEN);
        }
        return username;
    }

    private ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", message);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}
