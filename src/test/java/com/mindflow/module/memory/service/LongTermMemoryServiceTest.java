package com.mindflow.module.memory.service;

import com.mindflow.module.memory.entity.LongTermMemoryEntry;
import com.mindflow.module.memory.repository.LongTermMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryServiceTest {

    private LongTermMemoryRepository repository;
    private LongTermMemoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(LongTermMemoryRepository.class);
        service = new LongTermMemoryService(repository);
    }

    @Test
    void saveStoresNormalizedFact() {
        when(repository.existsByUserIdAndContent("u1", "用户偏好深色主题")).thenReturn(false);

        assertTrue(service.save("u1", "  用户偏好深色主题  "));
        verify(repository).save(any(LongTermMemoryEntry.class));
    }

    @Test
    void saveRejectsDuplicateOrBlank() {
        when(repository.existsByUserIdAndContent("u1", "已存在")).thenReturn(true);
        assertFalse(service.save("u1", "已存在"));
        assertFalse(service.save("u1", "   "));
        assertFalse(service.save(null, "fact"));
        verify(repository, never()).save(any());
    }

    @Test
    void deleteOnlyOwnEntries() {
        LongTermMemoryEntry entry = new LongTermMemoryEntry();
        entry.setUserId("u1");
        entry.setContent("fact");
        when(repository.findById(7L)).thenReturn(Optional.of(entry));

        assertTrue(service.delete(7L, "u1"));
        assertFalse(service.delete(7L, "u2"));
    }

    @Test
    void buildContextMatchesKeywordsThenFallsBackToLatest() {
        LongTermMemoryEntry hit = entry("用户偏好 Java 21 与简洁代码风格");
        LongTermMemoryEntry miss = entry("用户在北京办公");
        when(repository.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(hit, miss));

        String context = service.buildRelevantContext("u1", "java 代码风格怎么写", 3);
        assertTrue(context.contains("Java 21"));

        String fallback = service.buildRelevantContext("u1", "完全无关的查询词组", 1);
        assertTrue(fallback.contains("用户偏好 Java 21"));
        Mockito.verify(repository, Mockito.times(2)).findByUserIdOrderByCreatedAtDesc("u1");
    }

    private static LongTermMemoryEntry entry(String content) {
        LongTermMemoryEntry e = new LongTermMemoryEntry();
        e.setUserId("u1");
        e.setContent(content);
        e.setType("FACT");
        return e;
    }
}
