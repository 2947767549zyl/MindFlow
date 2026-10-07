package com.mindflow.module.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTraceCodecTest {

    private AgentTraceCodec codec;

    @BeforeEach
    void setUp() {
        codec = new AgentTraceCodec(new ObjectMapper());
    }

    private static Map<String, Object> event(String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        return event;
    }

    @Test
    void nullOrEmptyTraceProducesNothing() {
        assertNull(codec.toJson(null));
        assertNull(codec.toJson(List.of()));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("content", "answer");
        codec.applyToMessage(message, null);
        codec.applyToMessage(message, "  ");
        assertEquals("answer", message.get("content"));
        assertFalse(message.containsKey("toolEvents"));
    }

    @Test
    void mergesToolCallEventsByIdKeepingLatestFields() {
        Map<String, Object> executing = event("tool_call");
        executing.put("toolCallId", "call_1");
        executing.put("tool", "search_knowledge");
        executing.put("status", "executing");
        executing.put("arguments", "{\"query\":\"CAP\"}");

        Map<String, Object> success = event("tool_call");
        success.put("toolCallId", "call_1");
        success.put("tool", "search_knowledge");
        success.put("status", "success");
        success.put("resultPreview", "检索到 5 个片段");
        success.put("elapsedMs", 1234);

        Map<String, Object> message = new LinkedHashMap<>();
        codec.applyToMessage(message, codec.toJson(List.of(executing, success)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) message.get("toolEvents");
        assertEquals(1, tools.size(), "同一 toolCallId 应合并为一条");
        assertEquals("success", tools.get(0).get("status"));
        assertEquals("call_1", tools.get(0).get("id"));
        assertEquals("{\"query\":\"CAP\"}", tools.get(0).get("arguments"), "后到事件缺少参数时不应覆盖已有参数");
        assertEquals(1234L, tools.get(0).get("elapsedMs"));
    }

    @Test
    void mergesRoundEventsByRoundNumber() {
        Map<String, Object> started = event("agent_round");
        started.put("round", 1);
        started.put("phase", "started");

        Map<String, Object> finished = event("agent_round");
        finished.put("round", 1);
        finished.put("phase", "finished");
        finished.put("elapsedMs", 8400);
        finished.put("promptTokens", 1234);
        finished.put("completionTokens", 567);

        Map<String, Object> message = new LinkedHashMap<>();
        codec.applyToMessage(message, codec.toJson(List.of(started, finished)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rounds = (List<Map<String, Object>>) message.get("agentRounds");
        assertEquals(1, rounds.size());
        assertEquals("finished", rounds.get(0).get("phase"));
        assertEquals(8400L, rounds.get(0).get("elapsedMs"));
        assertEquals(1234, rounds.get(0).get("promptTokens"));
    }

    @Test
    void appendsFindingsAndDeduplicates() {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> finding = event("memory_finding");
            finding.put("round", 1);
            finding.put("tool", "search_knowledge");
            finding.put("summary", "知识库检索结果：CAP 三要素");
            events.add(finding);
        }
        Map<String, Object> other = event("memory_finding");
        other.put("round", 2);
        other.put("tool", "search_wiki");
        other.put("summary", "Wiki 概念页：CAP原则");
        events.add(other);

        Map<String, Object> message = new LinkedHashMap<>();
        codec.applyToMessage(message, codec.toJson(events));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) message.get("findings");
        assertEquals(2, findings.size(), "重复发现应去重");
    }

    @Test
    void appendsPlanAndTeamEventsIntoContent() {
        Map<String, Object> step = event("plan_step");
        step.put("status", "completed");
        step.put("stepId", "task_1");
        step.put("message", "读取资料");

        Map<String, Object> status = event("plan_status");
        status.put("state", "executing");
        status.put("progress", 0.5d);

        Map<String, Object> team = event("team_event");
        team.put("message", "worker-1 执行步骤 [step_1]");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("content", "最终回答");
        codec.applyToMessage(message, codec.toJson(List.of(step, status, team)));

        String content = String.valueOf(message.get("content"));
        assertTrue(content.startsWith("最终回答"));
        assertTrue(content.contains("✅ task_1 读取资料"));
        assertTrue(content.contains("📊 计划状态: executing（进度 50%）"));
        assertTrue(content.contains("worker-1 执行步骤 [step_1]"));
    }
}
