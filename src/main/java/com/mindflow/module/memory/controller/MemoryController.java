package com.mindflow.module.memory.controller;

import com.mindflow.module.memory.entity.LongTermMemoryEntry;
import com.mindflow.module.memory.service.LongTermMemoryService;
import com.mindflow.utils.JwtUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final LongTermMemoryService longTermMemoryService;
    private final JwtUtils jwtUtils;

    public MemoryController(LongTermMemoryService longTermMemoryService, JwtUtils jwtUtils) {
        this.longTermMemoryService = longTermMemoryService;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String token) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        List<LongTermMemoryEntry> entries = longTermMemoryService.listByUser(userId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", entries
        ));
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestHeader("Authorization") String token,
                                  @RequestBody Map<String, String> body) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        String fact = body.get("fact");
        boolean saved = longTermMemoryService.save(userId, fact);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", saved ? "已保存" : "记忆为空或已存在",
                "data", Map.of("saved", saved)
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@RequestHeader("Authorization") String token,
                                    @PathVariable Long id) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        boolean deleted = longTermMemoryService.delete(id, userId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", deleted ? "已删除" : "记录不存在或不属于当前用户",
                "data", Map.of("deleted", deleted)
        ));
    }
}
