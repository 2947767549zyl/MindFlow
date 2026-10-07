package com.mindflow.module.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 执行轨迹的编解码：把 WS 推送过的事件（tool_call / agent_round / memory_finding / plan_step /
 * plan_status / team_event）落库为 JSON，并在历史回放时还原成前端消息可直接渲染的形状。
 *
 * 合并语义（与前端实时 upsert 行为对齐）：
 * - tool_call 按 toolCallId 合并（executing → success/failed，字段后到者覆盖）
 * - agent_round 按轮次合并（started → finished）
 * - memory_finding 追加并按 轮次+工具+摘要 去重
 * - plan_step / plan_status / team_event 拼回 content（与实时会话中的内联文本一致）
 */
@Component
public class AgentTraceCodec {

    private static final Logger logger = LoggerFactory.getLogger(AgentTraceCodec.class);

    private final ObjectMapper objectMapper;

    public AgentTraceCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            logger.warn("序列化执行轨迹失败: {}", e.getMessage());
            return null;
        }
    }

    public void applyToMessage(Map<String, Object> message, String traceJson) {
        if (message == null || traceJson == null || traceJson.isBlank()) {
            return;
        }

        List<Map<String, Object>> events;
        try {
            events = objectMapper.readValue(traceJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            logger.warn("解析执行轨迹失败: {}", e.getMessage());
            return;
        }

        Map<String, Map<String, Object>> toolsByKey = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> roundsByNumber = new LinkedHashMap<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        Set<String> seenFindings = new HashSet<>();
        StringBuilder inline = new StringBuilder();

        for (Map<String, Object> event : events) {
            String type = String.valueOf(event.get("type"));
            switch (type) {
                case "tool_call" -> mergeToolEvent(toolsByKey, event);
                case "agent_round" -> mergeRoundEvent(roundsByNumber, event);
                case "memory_finding" -> addFinding(findings, seenFindings, event);
                case "plan_step" -> inline.append("\n\n").append(planStepLine(event));
                case "plan_status" -> inline.append("\n\n").append(planStatusLine(event));
                case "team_event" -> inline.append("\n\n").append(String.valueOf(event.getOrDefault("message", "")));
                default -> {
                }
            }
        }

        if (!toolsByKey.isEmpty()) {
            message.put("toolEvents", new ArrayList<>(toolsByKey.values()));
        }
        if (!roundsByNumber.isEmpty()) {
            message.put("agentRounds", new ArrayList<>(roundsByNumber.values()));
        }
        if (!findings.isEmpty()) {
            message.put("findings", findings);
        }
        if (inline.length() > 0) {
            String base = message.get("content") == null ? "" : String.valueOf(message.get("content"));
            message.put("content", base + inline);
        }
    }

    private void mergeToolEvent(Map<String, Map<String, Object>> toolsByKey, Map<String, Object> event) {
        String id = stringValue(event.get("toolCallId"));
        String tool = stringValue(event.get("tool"));
        String key = id.isEmpty() ? "tool:" + tool : id;

        Map<String, Object> item = toolsByKey.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        item.put("tool", tool);
        if (!id.isEmpty()) {
            item.put("id", id);
        }
        putIfNotNull(item, "status", event.get("status"));
        putIfNotNull(item, "arguments", event.get("arguments"));
        putIfNotNull(item, "resultPreview", event.get("resultPreview"));
        if (event.get("elapsedMs") instanceof Number elapsed && elapsed.longValue() > 0) {
            item.put("elapsedMs", elapsed.longValue());
        }
        if (event.get("timestamp") != null) {
            item.put("timestamp", event.get("timestamp"));
        }
    }

    private void mergeRoundEvent(Map<Integer, Map<String, Object>> roundsByNumber, Map<String, Object> event) {
        if (!(event.get("round") instanceof Number roundValue)) {
            return;
        }
        int round = roundValue.intValue();
        Map<String, Object> item = roundsByNumber.computeIfAbsent(round, key -> {
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("round", round);
            return created;
        });
        item.put("phase", "finished".equals(stringValue(event.get("phase"))) ? "finished" : "started");
        if (event.get("elapsedMs") instanceof Number elapsed) {
            item.put("elapsedMs", elapsed.longValue());
        }
        if (event.get("promptTokens") instanceof Number prompt) {
            item.put("promptTokens", prompt.intValue());
        }
        if (event.get("completionTokens") instanceof Number completion) {
            item.put("completionTokens", completion.intValue());
        }
        if (event.get("timestamp") != null) {
            item.put("timestamp", event.get("timestamp"));
        }
    }

    private void addFinding(List<Map<String, Object>> findings, Set<String> seen, Map<String, Object> event) {
        String summary = stringValue(event.get("summary"));
        if (summary.isEmpty()) {
            return;
        }
        int round = event.get("round") instanceof Number roundValue ? roundValue.intValue() : 0;
        String tool = stringValue(event.get("tool"));
        if (!seen.add(round + "|" + tool + "|" + summary)) {
            return;
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("round", round);
        item.put("tool", tool);
        item.put("summary", summary);
        if (event.get("timestamp") != null) {
            item.put("timestamp", event.get("timestamp"));
        }
        findings.add(item);
    }

    private String planStepLine(Map<String, Object> event) {
        String status = stringValue(event.get("status"));
        String icon = "started".equals(status) ? "▶️" : "completed".equals(status) ? "✅" : "❌";
        return (icon + " " + stringValue(event.get("stepId")) + " " + stringValue(event.get("message"))).trim();
    }

    private String planStatusLine(Map<String, Object> event) {
        double progress = event.get("progress") instanceof Number value ? value.doubleValue() : 0d;
        return "📊 计划状态: " + stringValue(event.get("state"))
                + "（进度 " + Math.round(progress * 100) + "%）";
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
