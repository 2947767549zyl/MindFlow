package com.mindflow.module.memory.service;

import com.mindflow.module.memory.entity.LongTermMemoryEntry;
import com.mindflow.module.memory.repository.LongTermMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 长期记忆（移植自 MindFlow memory.LongTermMemory，持久化从 JSON 文件改为 MySQL）。
 * 只保存跨会话仍成立的稳定事实；去重按 userId+content 精确匹配；
 * 注入上下文按查询关键词匹配，无命中时回退最近条目。
 */
@Service
public class LongTermMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemoryService.class);
    private static final int MAX_FACT_CHARS = 500;
    private static final int MAX_CONTEXT_CHARS = 1200;

    private final LongTermMemoryRepository repository;

    public LongTermMemoryService(LongTermMemoryRepository repository) {
        this.repository = repository;
    }

    public boolean save(String userId, String fact) {
        if (userId == null || userId.isBlank() || fact == null || fact.isBlank()) {
            return false;
        }
        String normalized = fact.trim();
        if (normalized.length() > MAX_FACT_CHARS) {
            normalized = normalized.substring(0, MAX_FACT_CHARS);
        }
        if (repository.existsByUserIdAndContent(userId, normalized)) {
            logger.info("长期记忆重复，跳过: userId={}", userId);
            return false;
        }
        LongTermMemoryEntry entry = new LongTermMemoryEntry();
        entry.setUserId(userId);
        entry.setContent(normalized);
        entry.setType("FACT");
        repository.save(entry);
        return true;
    }

    public List<LongTermMemoryEntry> listByUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public boolean delete(Long id, String userId) {
        if (id == null || userId == null || userId.isBlank()) {
            return false;
        }
        return repository.findById(id)
                .filter(entry -> userId.equals(entry.getUserId()))
                .map(entry -> {
                    repository.delete(entry);
                    return true;
                })
                .orElse(false);
    }

    public String buildRelevantContext(String userId, String query, int limit) {
        List<LongTermMemoryEntry> entries = listByUser(userId);
        if (entries.isEmpty()) {
            return "";
        }

        Set<String> queryTokens = tokenize(query);
        List<LongTermMemoryEntry> matched = new ArrayList<>();
        for (LongTermMemoryEntry entry : entries) {
            if (matches(entry.getContent(), queryTokens)) {
                matched.add(entry);
                if (matched.size() >= limit) {
                    break;
                }
            }
        }
        if (matched.isEmpty()) {
            matched = entries.subList(0, Math.min(limit, entries.size()));
        }

        StringBuilder sb = new StringBuilder("【长期记忆】以下是与当前任务可能相关的用户长期偏好/事实：\n");
        int chars = sb.length();
        for (LongTermMemoryEntry entry : matched) {
            String line = "- " + entry.getContent() + "\n";
            if (chars + line.length() > MAX_CONTEXT_CHARS) {
                break;
            }
            sb.append(line);
            chars += line.length();
        }
        return sb.toString().trim();
    }

    private Set<String> tokenize(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        if (query == null) {
            return tokens;
        }
        for (String token : query.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private boolean matches(String content, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return false;
        }
        String lower = content == null ? "" : content.toLowerCase();
        return queryTokens.stream().anyMatch(lower::contains);
    }
}
