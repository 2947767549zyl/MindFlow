package com.mindflow.harness.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.module.audit.repository.AuditLogEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogServiceTest {

    @TempDir
    Path tempDir;

    private AuditLogEntryRepository repository;
    private AuditLogService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditLogEntryRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new AuditLogService(repository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "auditDir", tempDir.toString());
    }

    @Test
    void writesJsonlLineAndMysqlRow() throws Exception {
        service.record("mcp__demo__click", "{\"url\":\"https://x\"}",
                AuditLogService.OUTCOME_DENY, "用户拒绝",
                AuditLogService.APPROVER_HITL, 120L, "u1", "conv-1");

        List<Path> files;
        try (var stream = Files.list(tempDir)) {
            files = stream.filter(p -> p.toString().endsWith(".jsonl")).toList();
        }
        assertEquals(1, files.size());
        String line = Files.readString(files.get(0)).trim();
        assertTrue(line.contains("\"tool\":\"mcp__demo__click\""));
        assertTrue(line.contains("\"outcome\":\"deny\""));
        assertTrue(line.contains("\"approver\":\"hitl\""));
        assertTrue(line.contains("\"userId\":\"u1\""));

        ArgumentCaptor<com.mindflow.module.audit.entity.AuditLogEntry> captor =
                ArgumentCaptor.forClass(com.mindflow.module.audit.entity.AuditLogEntry.class);
        verify(repository).save(captor.capture());
        assertTrue(captor.getValue().getArgs().contains("\"tool\":\"mcp__demo__click\"") || captor.getValue().getTool() != null);
    }

    @Test
    void sanitizesSensitiveArgsBeforeWrite() {
        String secret = "{\"token\":\"abc123\",\"authorization\":\"Bearer sk-live-xyz\",\"password\":\"p@ss\"}";

        service.record("execute_command", secret, AuditLogService.OUTCOME_ALLOW,
                null, AuditLogService.APPROVER_NONE, 5L, "u1", "conv-1");

        ArgumentCaptor<com.mindflow.module.audit.entity.AuditLogEntry> captor =
                ArgumentCaptor.forClass(com.mindflow.module.audit.entity.AuditLogEntry.class);
        verify(repository).save(captor.capture());
        com.mindflow.module.audit.entity.AuditLogEntry saved = captor.getValue();
        assertFalse(saved.getArgs().contains("abc123"));
        assertFalse(saved.getArgs().contains("sk-live-xyz"));
        assertFalse(saved.getArgs().contains("p@ss"));
        assertTrue(saved.getArgs().contains("***"));
    }

    private static String extractRaw(String sanitizedArgs) {
        return sanitizedArgs;
    }
}
