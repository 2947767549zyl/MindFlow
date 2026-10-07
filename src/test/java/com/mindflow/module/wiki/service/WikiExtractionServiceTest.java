package com.mindflow.module.wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.module.document.dto.TextChunk;
import com.mindflow.module.document.repository.DocumentVectorRepository;
import com.mindflow.module.wiki.repository.WikiPageRepository;
import com.mindflow.structure.ai.LlmProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WikiExtractionServiceTest {

    private WikiExtractionService service;

    @BeforeEach
    void setUp() {
        service = new WikiExtractionService(
                mock(DocumentVectorRepository.class),
                mock(LlmProviderRouter.class),
                mock(WikiPageService.class),
                mock(WikiPageRepository.class),
                new ObjectMapper());
    }

    private static TextChunk chunk(int id, String content) {
        return new TextChunk(id, content, null, null);
    }

    @Test
    void onlyChunksMentioningConceptBecomeEvidence() {
        List<TextChunk> chunks = List.of(
                chunk(1, "CAP原则强调一致性"),
                chunk(2, "完全无关的段落"),
                chunk(3, "CAP原则与可用性的权衡"));

        String evidence = service.buildEvidence("CAP原则", chunks, new int[]{100000});

        assertTrue(evidence.contains("chunk_1"));
        assertTrue(evidence.contains("chunk_3"));
        assertFalse(evidence.contains("chunk_2"), "未提及该概念的 chunk 不应作为证据");
    }

    @Test
    void evidenceCapsAtThreeChunksPerConcept() {
        List<TextChunk> chunks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            chunks.add(chunk(i, "CAP原则 证据" + i));
        }

        String evidence = service.buildEvidence("CAP原则", chunks, new int[]{100000});

        assertTrue(evidence.contains("chunk_1"));
        assertTrue(evidence.contains("chunk_3"));
        assertFalse(evidence.contains("chunk_4"), "每概念最多 3 段证据");
    }

    @Test
    void evidenceRespectsGlobalBudget() {
        List<TextChunk> chunks = List.of(
                chunk(1, "CAP原则 第一段"),
                chunk(2, "CAP原则 第二段"));

        String evidence = service.buildEvidence("CAP原则", chunks, new int[]{5});

        assertEquals(1, evidence.split("chunk_", -1).length - 1, "预算耗尽后不再追加证据");
    }

    @Test
    void emptyBudgetOrMissingConceptReturnsEmpty() {
        List<TextChunk> chunks = List.of(chunk(1, "CAP原则"));

        assertEquals("", service.buildEvidence("CAP原则", chunks, new int[]{0}));
        assertEquals("", service.buildEvidence("不存在的概念", chunks, new int[]{1000}));
        assertEquals("", service.buildEvidence(null, chunks, new int[]{1000}));
    }
}
