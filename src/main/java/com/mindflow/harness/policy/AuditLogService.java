package com.mindflow.harness.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.module.audit.entity.AuditLogEntry;
import com.mindflow.module.audit.repository.AuditLogEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 危险工具调用的结构化审计（L3，移植自 MindFlow policy.AuditLog，Web 化双写）。
 * JSONL 按天分文件 + MySQL audit_logs 双写；两侧写入失败都只告警，不阻断主流程。
 * 敏感参数（Bearer/token/key/password/secret/authorization）写入前统一脱敏。
 */
@Service
public class AuditLogService {

    public static final String APPROVER_HITL = "hitl";
    public static final String APPROVER_NONE = "none";

    public static final String OUTCOME_ALLOW = "allow";
    public static final String OUTCOME_DENY = "deny";
    public static final String OUTCOME_ERROR = "error";

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final int MAX_FIELD_CHARS = 1000;

    private final AuditLogEntryRepository repository;
    private final ObjectMapper mapper;
    private final Object writeLock = new Object();

    @Value("${mindflow.audit.dir:data/audit}")
    private String auditDir;

    public AuditLogService(AuditLogEntryRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public void record(String tool, String args, String outcome, String reason,
                       String approver, long durationMs, String userId, String conversationId) {
        if (tool == null || tool.isBlank()) {
            return;
        }
        String timestamp = Instant.now().toString();
        String safeArgs = truncate(sanitize(args));
        String safeReason = truncate(sanitize(reason));

        try {
            synchronized (writeLock) {
                Path dir = Path.of(auditDir);
                Files.createDirectories(dir);
                Path file = dir.resolve("audit-" + LocalDate.now().format(DATE_FMT) + ".jsonl");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("timestamp", timestamp);
                entry.put("tool", tool);
                entry.put("args", safeArgs);
                entry.put("outcome", outcome);
                entry.put("reason", safeReason);
                entry.put("approver", approver);
                entry.put("durationMs", durationMs);
                entry.put("userId", userId);
                entry.put("conversationId", conversationId);
                Files.writeString(file, mapper.writeValueAsString(entry) + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            logger.warn("审计 JSONL 写入失败: {}", e.getMessage());
        }

        try {
            AuditLogEntry entity = new AuditLogEntry();
            entity.setTimestamp(timestamp);
            entity.setTool(tool);
            entity.setArgs(safeArgs);
            entity.setOutcome(outcome);
            entity.setReason(safeReason);
            entity.setApprover(approver);
            entity.setDurationMs(durationMs);
            entity.setUserId(userId);
            entity.setConversationId(conversationId);
            repository.save(entity);
        } catch (Exception e) {
            logger.warn("审计 MySQL 双写失败: {}", e.getMessage());
        }
    }

    public List<AuditLogEntry> readRecentFromDb(int limit) {
        return repository.findTop50ByOrderByCreatedAtDesc().stream()
                .limit(Math.max(1, Math.min(limit, 50)))
                .toList();
    }

    static String sanitize(String s) {
        if (s == null) return null;
        String sanitized = s.replaceAll("(?i)Bearer\\s+[^\\s\"'}]+", "Bearer ***");
        sanitized = sanitized.replaceAll(
                "(?i)(\"?(?:token|key|password|secret|authorization)\"?\\s*[:=]\\s*\")([^\"]+)(\")",
                "$1***$3");
        sanitized = sanitized.replaceAll(
                "(?i)(\\b(?:token|key|password|secret|authorization)\\b\\s*[:=]\\s*)([^\\s,}]+)",
                "$1***");
        return sanitized;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        String sanitized = sanitize(s);
        return sanitized.length() <= MAX_FIELD_CHARS ? sanitized : sanitized.substring(0, MAX_FIELD_CHARS) + "...(truncated)";
    }
}
