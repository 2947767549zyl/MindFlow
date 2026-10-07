package com.mindflow.harness.skill;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级 Skill 正文缓冲：load_skill 写入，下一轮 user message 构造时 drain 取出前置，
 * 形成 `## 已加载 Skill：<name>` 段。一次性消费，单个 key 最多保留 3 个 skill body。
 * 按 userId 隔离（web 多用户场景的最小可行隔离）。
 */
@Component
public class SkillContextBuffer {

    private static final int MAX_BODIES = 3;

    private final Map<String, Deque<LoadedSkill>> buffers = new ConcurrentHashMap<>();

    public record LoadedSkill(String name, String body) {}

    public void offer(String userId, String name, String body) {
        if (userId == null || name == null || body == null || body.isBlank()) {
            return;
        }
        Deque<LoadedSkill> queue = buffers.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(new LoadedSkill(name, body));
            while (queue.size() > MAX_BODIES) {
                queue.removeFirst();
            }
        }
    }

    public boolean isEmpty(String userId) {
        Deque<LoadedSkill> queue = buffers.get(userId);
        return queue == null || queue.isEmpty();
    }

    /**
     * 取出并清空缓冲，渲染为可直接前置到 user 内容的段落；空缓冲返回空串。
     */
    public String drain(String userId) {
        Deque<LoadedSkill> queue = buffers.get(userId);
        if (queue == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        synchronized (queue) {
            while (!queue.isEmpty()) {
                LoadedSkill loaded = queue.pollFirst();
                sb.append("## 已加载 Skill：").append(loaded.name()).append("\n")
                        .append(loaded.body()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    public void clear(String userId) {
        buffers.remove(userId);
    }
}
