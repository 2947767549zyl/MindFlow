package com.mindflow.module.wiki.service;

import com.mindflow.module.wiki.entity.WikiPage;
import com.mindflow.module.wiki.repository.WikiPageRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WikiSearchServiceTest {

    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]]+)]]");

    private static WikiSearchService serviceWith(Map<String, String> pages) {
        WikiPageService wikiPageService = mock(WikiPageService.class);
        WikiPageRepository wikiPageRepository = mock(WikiPageRepository.class);

        when(wikiPageRepository.findAll()).thenReturn(List.of());
        when(wikiPageRepository.findByTitle(anyString())).thenReturn(java.util.Optional.empty());
        when(wikiPageService.getAllPageContents()).thenReturn(pages);
        when(wikiPageService.extractLinks(anyString())).thenAnswer(invocation -> {
            Object raw = invocation.getArgument(0);
            Set<String> links = new LinkedHashSet<>();
            Matcher matcher = LINK.matcher(String.valueOf(raw));
            while (matcher.find()) {
                links.add(matcher.group(1).trim());
            }
            return links;
        });

        return new WikiSearchService(wikiPageService, wikiPageRepository);
    }

    private static Map<String, String> capPages() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("CAP原则", "# CAP原则\n## 关联概念\n[[CA模型]], [[CP模型]]\n## 被引用\n- [[BASE理论]]");
        pages.put("CA模型", "# CA模型\n## 关联概念\n[[一致性]]");
        pages.put("CP模型", "# CP模型\n一致性优先。");
        pages.put("一致性", "# 一致性\n强一致。");
        pages.put("BASE理论", "# BASE理论\n[[CAP原则]] 的延伸，基本可用。");
        return pages;
    }

    @Test
    void exactTitleMatches() {
        assertFalse(serviceWith(capPages()).search("CAP原则", 3).isEmpty());
    }

    @Test
    void interrogativeChineseQueryStillFindsPage() {
        var hits = serviceWith(capPages()).search("什么是CAP原则", 3);

        assertFalse(hits.isEmpty());
        assertEquals("CAP原则", hits.get(0).title());
    }

    @Test
    void naturalLanguageQueryStillFindsPage() {
        assertFalse(serviceWith(capPages()).search("CAP原则有哪些要素", 3).isEmpty());
    }

    @Test
    void unrelatedQueryReturnsEmpty() {
        assertTrue(serviceWith(capPages()).search("今天天气怎么样", 3).isEmpty());
    }

    @Test
    void titleVariantSharingLatinPrefixStillHits() {
        var hits = serviceWith(capPages()).search("什么是CAP原理", 3);

        assertFalse(hits.isEmpty(), "提问 CAP原理 应兜住标题 CAP原则");
        assertEquals("CAP原则", hits.get(0).title());
    }

    @Test
    void synonymGroupMatchesWithoutSharedCharacters() {
        Map<String, String> pages = Map.of("宕机排查", "# 宕机排查\n服务故障时的定位步骤。");

        var hits = serviceWith(pages).search("崩溃排查", 3);

        assertFalse(hits.isEmpty(), "同义词组（宕机/崩溃/故障）应能跨词命中");
        assertEquals("宕机排查", hits.get(0).title());
    }

    @Test
    void aliasLineMakesPageFindable() {
        Map<String, String> pages = Map.of(
                "CAP原则", "# CAP原则\n别名: CAP定理, CAP理论\n一致性、可用性、分区容错性。");

        var hits = serviceWith(pages).search("什么是CAP定理", 3);

        assertFalse(hits.isEmpty(), "页面声明的别名应可被检索命中");
        assertEquals("CAP原则", hits.get(0).title());
    }

    @Test
    void pureChineseSynonymWithoutSharedCharsMisses() {
        Map<String, String> pages = Map.of("分区容错性", "# 分区容错性\n网络分区故障仍能提供服务。");

        assertTrue(serviceWith(pages).search("什么是脑裂", 3).isEmpty(),
                "无共享字符且不在同义词组内时，词法检索仍无法命中（语义检索/T2 的动机）");
    }

    @Test
    void relatedConceptsReturnsForwardLinks() {
        List<String> related = serviceWith(capPages()).relatedConcepts("CAP原则");

        assertTrue(related.contains("CA模型"));
        assertTrue(related.contains("CP模型"));
        assertEquals(2, related.size());
    }

    @Test
    void navigateWithHopsExpandsOneLevel() {
        var result = serviceWith(capPages()).navigateWithHops("CAP原则", 1);

        assertTrue(result.isPresent());
        assertEquals("CAP原则", result.get().title());
        assertEquals(2, result.get().related().size());
        assertTrue(result.get().related().stream().anyMatch(page -> page.title().equals("CA模型")));
        assertTrue(result.get().backlinks().contains("BASE理论"));
    }

    @Test
    void navigateWithHopsExpandsTwoLevels() {
        var result = serviceWith(capPages()).navigateWithHops("CAP原则", 2);

        assertTrue(result.isPresent());
        assertEquals(3, result.get().related().size(), "两跳应再带出 CA模型 的关联页 一致性");
        assertTrue(result.get().related().stream().anyMatch(page -> page.title().equals("一致性")));
    }

    @Test
    void navigateWithHopsUnknownConceptReturnsEmpty() {
        assertTrue(serviceWith(capPages()).navigateWithHops("不存在的概念", 1).isEmpty());
    }

    @Test
    void dbPagesAndFilePagesAreMergedWithDbPriority() {
        WikiPageService wikiPageService = mock(WikiPageService.class);
        WikiPageRepository wikiPageRepository = mock(WikiPageRepository.class);

        WikiPage dbPage = new WikiPage();
        dbPage.setTitle("新文档概念");
        dbPage.setContent("# 新文档概念\n来自数据库。");

        when(wikiPageRepository.findAll()).thenReturn(List.of(dbPage));
        when(wikiPageRepository.findByTitle(anyString())).thenReturn(java.util.Optional.empty());
        when(wikiPageService.getAllPageContents()).thenReturn(Map.of("CAP原则", "# CAP原则\n来自文件系统。"));
        when(wikiPageService.extractLinks(anyString())).thenReturn(Set.of());

        WikiSearchService merged = new WikiSearchService(wikiPageService, wikiPageRepository);

        Map<String, String> pages = merged.getAllPageContents();
        assertTrue(pages.containsKey("新文档概念"), "数据库页面应可见");
        assertTrue(pages.containsKey("CAP原则"), "文件系统历史页面不应因数据库有数据而消失");
    }
}
