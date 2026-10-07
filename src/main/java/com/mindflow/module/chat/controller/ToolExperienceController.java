package com.mindflow.module.chat.controller;

import com.mindflow.module.chat.entity.ToolExperience;
import com.mindflow.module.chat.service.ToolExperienceService;
import com.mindflow.utils.JwtUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具失败经验库（只读元数据）：给前端「经验记忆库」页展示 Agent 踩过的坑与恢复策略。
 */
@RestController
@RequestMapping("/api/v1/agent/experience")
public class ToolExperienceController {

    private final ToolExperienceService experienceService;
    private final JwtUtils jwtUtils;

    public ToolExperienceController(ToolExperienceService experienceService, JwtUtils jwtUtils) {
        this.experienceService = experienceService;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String token) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "无效的token"));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        int totalOccurrences = 0;
        java.util.Set<String> tools = new java.util.LinkedHashSet<>();
        for (ToolExperience experience : experienceService.listExperiences()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolName", experience.getToolName());
            item.put("failureCategory", experience.getFailureCategory());
            item.put("failureLabel", ToolExperienceService.labelOf(experience.getFailureCategory()));
            item.put("occurrenceCount", experience.getOccurrenceCount());
            item.put("recoveryHint", experience.getRecoveryHint());
            item.put("lastErrorText", experience.getLastErrorText());
            item.put("firstSeenAt", experience.getFirstSeenAt() == null ? null : experience.getFirstSeenAt().toString());
            item.put("lastSeenAt", experience.getLastSeenAt() == null ? null : experience.getLastSeenAt().toString());
            items.add(item);
            totalOccurrences += experience.getOccurrenceCount();
            tools.add(experience.getToolName());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("experienceCount", items.size());
        data.put("toolCount", tools.size());
        data.put("totalOccurrences", totalOccurrences);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "success");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}
