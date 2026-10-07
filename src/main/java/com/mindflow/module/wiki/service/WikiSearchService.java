package com.mindflow.module.wiki.service;

import com.mindflow.module.wiki.entity.WikiPage;
import com.mindflow.module.wiki.repository.WikiPageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class WikiSearchService {

    private static final Logger logger = LoggerFactory.getLogger(WikiSearchService.class);

    private final WikiPageService wikiPageService;
    private final WikiPageRepository wikiPageRepository;

    public WikiSearchService(WikiPageService wikiPageService, WikiPageRepository wikiPageRepository) {
        this.wikiPageService = wikiPageService;
        this.wikiPageRepository = wikiPageRepository;
    }

    /**
     * 搜索 Wiki 页面，按关键词匹配度排序返回
     */
    public List<WikiSearchResult> search(String query, int topK) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        Map<String, String> allPages = getAllPageContents();
        if (allPages.isEmpty()) return Collections.emptyList();

        List<String> terms = buildCandidateTerms(query);
        if (terms.isEmpty()) {
            return Collections.emptyList();
        }

        List<WikiSearchResult> scored = new ArrayList<>();
        for (Map.Entry<String, String> entry : allPages.entrySet()) {
            String title = entry.getKey();
            String content = entry.getValue();
            double score = computeRelevance(title, content, terms);
            if (score > 0) {
                scored.add(new WikiSearchResult(title, content, score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream().limit(Math.max(1, topK)).collect(Collectors.toList());
    }

    /**
     * 按标题精确导航到指定概念页面，并提取反向链接（navigate_wiki 工具后端）
     */
    public Optional<WikiNavigateResult> navigate(String targetConcept) {
        if (targetConcept == null || targetConcept.isBlank()) {
            return Optional.empty();
        }
        String title = targetConcept.trim();

        String content = null;
        try {
            content = wikiPageRepository.findByTitle(title).map(WikiPage::getContent).orElse(null);
        } catch (Exception e) {
            logger.warn("wiki_pages 主键查询失败，回退文件系统: {}", e.getMessage());
        }
        if (content == null) {
            content = getAllPageContents().get(title);
        }
        if (content == null) {
            return Optional.empty();
        }
        return Optional.of(new WikiNavigateResult(title, content, extractBacklinks(title)));
    }

    private List<String> extractBacklinks(String title) {
        List<String> sources = new ArrayList<>();
        for (Map.Entry<String, String> entry : getAllPageContents().entrySet()) {
            if (entry.getKey().equals(title)) {
                continue;
            }
            if (wikiPageService.extractLinks(entry.getValue()).contains(title)) {
                sources.add(entry.getKey());
            }
        }
        return sources;
    }

    /**
     * 全量页面读取：优先 MySQL wiki_pages（多实例一致），为空时回退文件系统（历史数据兼容）
     */
    public Map<String, String> getAllPageContents() {
        Map<String, String> merged = new LinkedHashMap<>();
        try {
            for (WikiPage page : wikiPageRepository.findAll()) {
                merged.put(page.getTitle(), page.getContent() == null ? "" : page.getContent());
            }
        } catch (Exception e) {
            logger.warn("读取 MySQL wiki_pages 失败，回退文件系统: {}", e.getMessage());
        }

        // 必须补齐文件系统里的历史页面（DB 同名页面优先）：否则 wiki_pages 出现首条记录后，
        // 纯文件系统的历史页面会整体从检索与知识图谱中消失。
        for (Map.Entry<String, String> entry : wikiPageService.getAllPageContents().entrySet()) {
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    private static final Set<String> QUERY_FILLERS = Set.of(
            "什么是", "是什么", "请问", "介绍一下", "介绍", "解释一下", "解释", "说明一下", "说明",
            "如何", "怎么样", "怎么", "为什么", "有哪些", "有哪", "哪些", "关于", "的", "呢", "吗", "嘛", "啊", "吧");

    /**
     * 中文没有空格分词，直接拿整句去 contains 匹配会漏检（如「什么是CAP原理」匹配不到标题「CAP原理」）。
     * 因此先剥离疑问/语气词，再生成候选词：紧凑整句 + 空白分词 + 2~4 字 n-gram，取最佳命中。
     */
    private List<String> buildCandidateTerms(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        for (String filler : QUERY_FILLERS) {
            normalized = normalized.replace(filler, " ");
        }
        normalized = normalized.replaceAll("[\\s,，、。！？!?；;：:（）()【】\\[\\]\"'`]+", " ").trim();
        String compact = normalized.replace(" ", "");
        if (compact.isEmpty()) {
            compact = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        }
        if (compact.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(compact);
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        for (int n = Math.min(4, compact.length()); n >= 2; n--) {
            for (int i = 0; i + n <= compact.length(); i++) {
                terms.add(compact.substring(i, i + n));
            }
        }
        return new ArrayList<>(terms);
    }

    /**
     * 计算页面与查询的相关性分数：对标题/别名取候选词最佳命中，并叠加同义词组与标题模糊匹配
     */
    private double computeRelevance(String title, String content, List<String> terms) {
        String contentLower = content.toLowerCase(Locale.ROOT);
        List<String> titleCandidatesLower = new ArrayList<>();
        for (String candidate : titleCandidates(title, content)) {
            titleCandidatesLower.add(candidate.toLowerCase(Locale.ROOT));
        }

        // 提取双向链接中的关联概念
        Set<String> links = wikiPageService.extractLinks(content);

        double best = 0.0;
        for (String term : terms) {
            for (String titleLower : titleCandidatesLower) {
                best = Math.max(best, scoreTerm(titleLower, contentLower, links, term));
                best = Math.max(best, synonymGroupScore(titleLower, term));
                best = Math.max(best, fuzzyTitleScore(titleLower, term));
            }
        }
        return best;
    }

    private double scoreTerm(String titleLower, String contentLower, Set<String> links, String term) {
        if (titleLower.equals(term)) {
            return 100.0;
        }
        if (titleLower.contains(term)) {
            return 10.0;
        }
        if (contentLower.contains(term)) {
            return 5.0;
        }
        for (String link : links) {
            if (link.toLowerCase(Locale.ROOT).contains(term)) {
                return 6.0;
            }
        }
        return 0.0;
    }

    private List<String> titleCandidates(String title, String content) {
        List<String> candidates = new ArrayList<>();
        candidates.add(title);
        candidates.addAll(parseAliases(content));
        return candidates;
    }

    private List<String> parseAliases(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        Matcher matcher = ALIAS_LINE_PATTERN.matcher(content);
        while (matcher.find()) {
            for (String part : matcher.group(1).split("[,，、;；|]+")) {
                String alias = part.trim();
                if (!alias.isEmpty()) {
                    aliases.add(alias);
                }
            }
        }
        return aliases;
    }

    private double synonymGroupScore(String titleLower, String term) {
        for (List<String> group : SYNONYM_GROUPS) {
            boolean titleHasMember = false;
            boolean termHasMember = false;
            for (String member : group) {
                if (titleLower.contains(member)) {
                    titleHasMember = true;
                }
                if (term.contains(member)) {
                    termHasMember = true;
                }
            }
            if (titleHasMember && termHasMember) {
                return 8.0;
            }
        }
        return 0.0;
    }

    private double fuzzyTitleScore(String titleLower, String term) {
        if (term.length() < 2 || titleLower.length() < 2) {
            return 0.0;
        }
        double similarity = diceCoefficient(titleLower, term);
        return similarity >= FUZZY_TITLE_THRESHOLD ? 8.0 * similarity : 0.0;
    }

    private double diceCoefficient(String left, String right) {
        Set<String> leftGrams = bigrams(left);
        Set<String> rightGrams = bigrams(right);
        if (leftGrams.isEmpty() || rightGrams.isEmpty()) {
            return 0.0;
        }
        long common = leftGrams.stream().filter(rightGrams::contains).count();
        return (2.0 * common) / (leftGrams.size() + rightGrams.size());
    }

    private Set<String> bigrams(String value) {
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= value.length(); i++) {
            grams.add(value.substring(i, i + 2));
        }
        return grams;
    }

    public List<String> relatedConcepts(String title) {
        String content = getPageContent(title);
        return content == null ? List.of() : new ArrayList<>(forwardLinks(content));
    }

    /**
     * 一次性返回所有页面的正向关联概念（图谱批量构建用，避免逐页重复读取全量页面）。
     */
    public Map<String, List<String>> allForwardLinks() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : getAllPageContents().entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(forwardLinks(entry.getValue())));
        }
        return result;
    }

    private Set<String> forwardLinks(String content) {
        if (content == null || content.isEmpty()) {
            return Set.of();
        }
        int backlinkIndex = content.indexOf(BACKLINK_HEADING);
        String forwardPart = backlinkIndex >= 0 ? content.substring(0, backlinkIndex) : content;
        return wikiPageService.extractLinks(forwardPart);
    }

    /**
     * 多跳导航：沿 [[关联概念]] 正向展开 hops 跳（上限 2，关联页上限 6，单页截断 1200 字），
     * 返回主页面 + 展开的关联页 + 反向链接，供 navigate_wiki 工具做多跳推理。
     */
    public Optional<WikiMultiHopResult> navigateWithHops(String targetConcept, int hops) {
        if (targetConcept == null || targetConcept.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> allPages = getAllPageContents();
        String rootTitle = resolveTitle(targetConcept, allPages);
        if (rootTitle == null) {
            return Optional.empty();
        }

        int maxHops = Math.max(1, Math.min(hops, MAX_HOPS));
        List<WikiLinkPage> related = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(rootTitle);
        List<String> frontier = List.of(rootTitle);

        for (int level = 0; level < maxHops && related.size() < MAX_RELATED_PAGES; level++) {
            List<String> next = new ArrayList<>();
            for (String current : frontier) {
                for (String linked : forwardLinks(allPages.getOrDefault(current, ""))) {
                    String resolved = resolveTitle(linked, allPages);
                    if (resolved == null || visited.contains(resolved)) {
                        continue;
                    }
                    visited.add(resolved);
                    next.add(resolved);
                    related.add(new WikiLinkPage(resolved, clip(allPages.get(resolved), MAX_RELATED_CHARS)));
                    if (related.size() >= MAX_RELATED_PAGES) {
                        break;
                    }
                }
                if (related.size() >= MAX_RELATED_PAGES) {
                    break;
                }
            }
            frontier = next;
        }

        return Optional.of(new WikiMultiHopResult(
                rootTitle, allPages.get(rootTitle), extractBacklinks(rootTitle), related));
    }

    private String getPageContent(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        try {
            String fromDb = wikiPageRepository.findByTitle(title).map(WikiPage::getContent).orElse(null);
            if (fromDb != null) {
                return fromDb;
            }
        } catch (Exception e) {
            logger.warn("wiki_pages 主键查询失败: {}", e.getMessage());
        }
        return getAllPageContents().get(title);
    }

    private String resolveTitle(String raw, Map<String, String> allPages) {
        if (raw == null) {
            return null;
        }
        String candidate = raw.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        if (allPages.containsKey(candidate)) {
            return candidate;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        for (String title : allPages.keySet()) {
            if (title.toLowerCase(Locale.ROOT).equals(lower)) {
                return title;
            }
        }
        return null;
    }

    private String clip(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxChars ? content : content.substring(0, maxChars) + "\n...[已截断]";
    }

    private static final Pattern ALIAS_LINE_PATTERN = Pattern.compile("(?im)^\\s*(?:别名|aliases?)\\s*[:：]\\s*(.+)$");
    private static final String BACKLINK_HEADING = "## 被引用";
    private static final double FUZZY_TITLE_THRESHOLD = 0.6;
    private static final int MAX_HOPS = 2;
    private static final int MAX_RELATED_PAGES = 6;
    private static final int MAX_RELATED_CHARS = 1200;

    private static final List<List<String>> SYNONYM_GROUPS = List.of(
            List.of("原则", "原理", "准则"),
            List.of("定理", "定律", "理论"),
            List.of("宕机", "故障", "崩溃"),
            List.of("一致性", "强一致"),
            List.of("可用性", "高可用"),
            List.of("消息队列", "mq"),
            List.of("分布式锁", "分布式互斥"),
            List.of("两阶段提交", "2pc"),
            List.of("三阶段提交", "3pc"));

    public record WikiSearchResult(String title, String markdown, double score) {}

    public record WikiNavigateResult(String title, String markdown, List<String> backlinks) {}

    public record WikiLinkPage(String title, String markdown) {}

    public record WikiMultiHopResult(String title, String markdown, List<String> backlinks,
                                     List<WikiLinkPage> related) {}
}
