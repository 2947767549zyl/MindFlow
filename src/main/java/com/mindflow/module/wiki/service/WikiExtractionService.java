package com.mindflow.module.wiki.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.module.document.dto.TextChunk;
import com.mindflow.module.document.repository.DocumentVectorRepository;
import com.mindflow.module.wiki.dto.PartialWikiEntry;
import com.mindflow.module.wiki.entity.WikiPage;
import com.mindflow.module.wiki.repository.WikiPageRepository;
import com.mindflow.structure.ai.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class WikiExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(WikiExtractionService.class);
    private static final int BATCH_SIZE = 10;
    private static final int MAX_COMPLETION_TOKENS = 2048;
    private static final int MAX_REDUCE_TOKENS = 40000;
    private static final int MAX_REDUCE_RETRIES = 3;
    // 单批 Reduce 的“预估输出 token”预算：留足余量低于 MAX_REDUCE_TOKENS，避免整体截断
    private static final int REDUCE_OUTPUT_BUDGET_TOKENS = 30000;
    // 重试时 max_tokens 逐次翻倍的硬上限
    private static final int MAX_REDUCE_TOKENS_HARD_CAP = 64000;
    // 输出 token 粗估参数（宁多勿少，使分批更保守）
    private static final int REDUCE_MIN_PAGE_CHARS = 600;
    private static final int REDUCE_CHARS_PER_TOKEN = 2;
    private static final int REDUCE_PAGE_OVERHEAD_TOKENS = 120;
    private final DocumentVectorRepository documentVectorRepository;
    private final LlmProviderRouter llmProviderRouter;
    private final WikiPageService wikiPageService;
    private final WikiPageRepository wikiPageRepository;
    private final ObjectMapper objectMapper;

    public WikiExtractionService(DocumentVectorRepository documentVectorRepository,
                                 LlmProviderRouter llmProviderRouter,
                                 WikiPageService wikiPageService,
                                 WikiPageRepository wikiPageRepository,
                                 ObjectMapper objectMapper) {
        this.documentVectorRepository = documentVectorRepository;
        this.llmProviderRouter = llmProviderRouter;
        this.wikiPageService = wikiPageService;
        this.wikiPageRepository = wikiPageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 对单个文档执行 Wiki 提取
     * 流程：读取 Chunks → Map（分批提取）→ Reduce（合并去重）→ 写文件 → 双向链接
     */
    public void extract(String fileMd5, String fileName, String userId) {
        logger.info("开始 Wiki 提取: fileMd5={}, fileName={}", fileMd5, fileName);

        // Step 1: 从 MySQL 读取该文档所有 Chunks（已有的，按 chunkId 排序）
        List<TextChunk> chunks = fetchChunks(fileMd5);
        if (chunks.isEmpty()) {
            logger.warn("无可提取的 Chunk: fileMd5={}", fileMd5);
            return;
        }
        logger.info("共 {} 个 Chunk，批次大小={}，开始 Map 阶段", chunks.size(), BATCH_SIZE);

        // Step 2: Map 阶段 — 分批局部提取
        List<PartialWikiEntry> allEntries = new ArrayList<>();
        int batchIndex = 0;
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            List<TextChunk> batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
            batchIndex++;
            try {
                List<PartialWikiEntry> batchEntries = extractBatch(batch, fileName, batchIndex);
                allEntries.addAll(batchEntries);
                logger.info("第 {} 批提取完成，获得 {} 个条目", batchIndex, batchEntries.size());
            } catch (Exception e) {
                logger.error("第 {} 批提取失败，跳过", batchIndex, e);
            }
        }

        if (allEntries.isEmpty()) {
            logger.warn("Map 阶段未提取到任何条目: fileMd5={}", fileMd5);
            return;
        }
        logger.info("Map 阶段完成，共 {} 个条目，开始 Reduce 阶段", allEntries.size());

        // Step 3: Reduce 阶段 — 全局合并去重
        List<Map<String, Object>> finalEntries = mergeEntries(allEntries, fileName, chunks);
        if (finalEntries.isEmpty()) {
            logger.warn("Reduce 阶段未生成任何 Wiki 页面: fileMd5={}", fileMd5);
            return;
        }

        // Step 4: 双写文件系统 + MySQL（wiki_pages 为查询主源，文件系统保留兼容）
        int savedCount = 0;
        for (Map<String, Object> entry : finalEntries) {
            String title = (String) entry.get("title");
            String markdown = (String) entry.get("markdown");
            if (title == null || title.isBlank() || markdown == null || markdown.isBlank()) continue;
            wikiPageService.saveOrUpdate(title, markdown);
            try {
                WikiPage page = wikiPageRepository.findByTitle(title).orElseGet(WikiPage::new);
                page.setTitle(title);
                page.setContent(markdown);
                page.setSourceFile(fileName);
                wikiPageRepository.save(page);
                savedCount++;
            } catch (Exception e) {
                logger.warn("wiki_pages 双写失败（文件系统已写入）: title={}, error={}", title, e.getMessage());
            }
        }
        logger.info("Wiki 页面写入完成，共 {} 个页面（MySQL 成功 {}）", finalEntries.size(), savedCount);

        // Step 5: 处理双向链接
        wikiPageService.resolveBidirectionalLinks();

        logger.info("Wiki 提取完成: fileMd5={}, fileName={}", fileMd5, fileName);
    }

    /**
     * Map 阶段：对一批 Chunk 做局部概念提取
     * 输入：5-10 个 Chunk 文本
     * 输出：该批次中出现的概念条目列表（JSON 格式）
     */
    private List<PartialWikiEntry> extractBatch(List<TextChunk> batch, String fileName, int batchIndex) {
        StringBuilder batchText = new StringBuilder();
        String chunkPrefix = "--- chunk_%d ---\n";
        for (TextChunk chunk : batch) {
            batchText.append(String.format(chunkPrefix, chunk.getChunkId()));
            batchText.append(chunk.getContent()).append("\n\n");
        }

        String prompt = String.format("""
                从以下文档片段中提取独立的概念条目。
                每个条目代表一个独立的知识主题、实体或概念。

                提取要求：
                - 概念名称：用具体的名词命名（如"向量数据库"、"张三"、"RAG技术"）
                - 定义：用一句话概括该概念是什么
                - 关键信息：提炼 2-4 个核心要点，尽量具体（含数字、参数、条件、机制细节），避免空泛表述
                - 关联概念：列出该片段中与之相关的其他概念名称
                - 别名：该概念在文中出现的同义写法/英文缩写/全称（如 "CAP原则" 的别名 "CAP定理"、"CAP理论"）；没有则留空数组

                输出格式（JSON 数组）：
                [
                  {
                    "name": "概念名称",
                    "definition": "定义描述",
                    "keyInfo": ["要点1", "要点2"],
                    "relatedConcepts": ["关联概念A", "关联概念B"],
                    "aliases": ["别名1", "别名2"],
                    "sourceChunkId": "chunk_编号"
                  }
                ]

                文档片段（第%d批，来源：%s）：
                %s
                """, batchIndex, fileName, batchText);

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", "你是一个文档概念提取助手。只输出 JSON 数组，不要额外说明或代码块标记。"),
                Map.of("role", "user", "content", prompt)
        );

        var turn = llmProviderRouter.completeReActTurn(
                "system", messages, null, MAX_COMPLETION_TOKENS);

        return parseBatchResponse(turn.content(), batch);
    }

    /**
     * 解析 Map 阶段的 LLM 输出为 PartialWikiEntry 列表
     */
    @SuppressWarnings("unchecked")
    private List<PartialWikiEntry> parseBatchResponse(String llmOutput, List<TextChunk> batch) {
        if (llmOutput == null || llmOutput.isBlank()) return Collections.emptyList();

        try {
            String json = extractJsonArray(llmOutput);
            if (json == null) return Collections.emptyList();

            List<Map<String, Object>> parsed = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});

            List<PartialWikiEntry> entries = new ArrayList<>();
            String defaultChunkId = batch.isEmpty() ? "" : "chunk_" + batch.get(0).getChunkId();

            for (Map<String, Object> item : parsed) {
                PartialWikiEntry entry = new PartialWikiEntry();
                entry.setName(safeString(item.get("name")));
                entry.setDefinition(safeString(item.get("definition")));
                entry.setKeyInfo(safeList(item.get("keyInfo")));
                entry.setRelatedConcepts(safeList(item.get("relatedConcepts")));
                entry.setAliases(safeList(item.get("aliases")));

                String sourceId = safeString(item.get("sourceChunkId"));
                entry.setSourceChunkId(sourceId.isEmpty() ? defaultChunkId : sourceId);

                if (!entry.getName().isEmpty()) {
                    entries.add(entry);
                }
            }
            return entries;
        } catch (Exception e) {
            logger.warn("解析批次提取结果失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Reduce 阶段：合并所有局部条目，去重、结构化、加链接、加溯源
     * 输入：Map 阶段产出的所有 PartialWikiEntry
     * 输出：完整的 Wiki 页面列表（含标题和 Markdown 内容）
     */
    private List<Map<String, Object>> mergeEntries(List<PartialWikiEntry> allEntries, String fileName,
                                                    List<TextChunk> chunks) {
        // 按“预估输出 token”把条目切成多个 Reduce 批次：单批输出可控，不再因超过 max_tokens 被整体截断。
        List<List<PartialWikiEntry>> batches = groupByOutputBudget(allEntries);
        logger.info("Reduce 分批：{} 个条目拆成 {} 个批次（单批预估输出预算 {} token）",
                allEntries.size(), batches.size(), REDUCE_OUTPUT_BUDGET_TOKENS);

        // 跨批次按标题去重合并（同名保留内容更完整的一页）。
        Map<String, Map<String, Object>> mergedByTitle = new LinkedHashMap<>();
        for (int b = 0; b < batches.size(); b++) {
            List<Map<String, Object>> pages = reduceBatch(batches.get(b), fileName, chunks, b + 1, batches.size());
            for (Map<String, Object> page : pages) {
                String title = safeString(page.get("title"));
                if (title.isEmpty()) {
                    continue;
                }
                Map<String, Object> existing = mergedByTitle.get(title);
                if (existing == null
                        || safeString(page.get("markdown")).length() > safeString(existing.get("markdown")).length()) {
                    mergedByTitle.put(title, page);
                }
            }
        }

        if (mergedByTitle.isEmpty()) {
            logger.error("Reduce 阶段所有批次均未产出页面（每批已重试 {} 次）", MAX_REDUCE_RETRIES);
        }
        return new ArrayList<>(mergedByTitle.values());
    }

    /**
     * 按预估输出 token 分组：每组累计预估输出不超过 REDUCE_OUTPUT_BUDGET_TOKENS；
     * 每组至少 1 个条目（单条目再大也独立成组，交给截断抢救兜底）。
     */
    private List<List<PartialWikiEntry>> groupByOutputBudget(List<PartialWikiEntry> allEntries) {
        List<List<PartialWikiEntry>> batches = new ArrayList<>();
        List<PartialWikiEntry> current = new ArrayList<>();
        int currentEstimate = 0;
        for (PartialWikiEntry e : allEntries) {
            int estimate = estimatePageOutputTokens(e);
            if (!current.isEmpty() && currentEstimate + estimate > REDUCE_OUTPUT_BUDGET_TOKENS) {
                batches.add(current);
                current = new ArrayList<>();
                currentEstimate = 0;
            }
            current.add(e);
            currentEstimate += estimate;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /**
     * 粗估一个条目经 Reduce 后产出的整页 token 数：以条目自带文本量做依据，
     * 整页约为条目文本量的 2 倍字符，再折算 token 并加固定开销（宁多勿少）。
     */
    private int estimatePageOutputTokens(PartialWikiEntry e) {
        int chars = safeString(e.getName()).length()
                + safeString(e.getDefinition()).length()
                + e.getKeyInfo().stream().mapToInt(String::length).sum()
                + e.getRelatedConcepts().stream().mapToInt(String::length).sum()
                + e.getAliases().stream().mapToInt(String::length).sum();
        int outputChars = Math.max(chars * 2, REDUCE_MIN_PAGE_CHARS);
        return (int) Math.ceil(outputChars / (double) REDUCE_CHARS_PER_TOKEN) + REDUCE_PAGE_OVERHEAD_TOKENS;
    }

    /**
     * 单批 Reduce：一次调用产出该子集的完整页面。命中截断时先尝试从已输出内容里抢救完整页面对象；
     * 若无可抢救则逐次抬高 max_tokens 重试（而非三次同值的无效重试）。
     */
    private List<Map<String, Object>> reduceBatch(List<PartialWikiEntry> batch, String fileName,
                                                  List<TextChunk> chunks, int batchNo, int batchTotal) {
        String prompt = buildReducePrompt(batch, fileName, chunks);
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", "你是一个 Wiki 知识库编辑助手。只输出 JSON 数组，不要额外说明或代码块标记。"),
                Map.of("role", "user", "content", prompt)
        );

        int maxTokens = MAX_REDUCE_TOKENS;
        for (int retry = 0; retry < MAX_REDUCE_RETRIES; retry++) {
            LlmProviderRouter.ReActTurn turn;
            try {
                turn = llmProviderRouter.completeReActTurn("system", messages, null, maxTokens);
            } catch (Exception e) {
                int next = Math.min(maxTokens * 2, MAX_REDUCE_TOKENS_HARD_CAP);
                logger.warn("Reduce 批次 {}/{} 调用异常（第 {} 次），max_tokens {}→{}：{}",
                        batchNo, batchTotal, retry + 1, maxTokens, next, e.getMessage());
                maxTokens = next;
                continue;
            }

            List<Map<String, Object>> salvaged = parsePagesTolerant(turn.content());
            if (!"length".equals(turn.finishReason())) {
                if (!salvaged.isEmpty()) {
                    return salvaged;
                }
                logger.warn("Reduce 批次 {}/{} 输出无法解析（第 {} 次），重试", batchNo, batchTotal, retry + 1);
            } else if (!salvaged.isEmpty()) {
                logger.warn("Reduce 批次 {}/{} 输出被截断，已抢救 {} 个完整页面（第 {} 次），继续下一批",
                        batchNo, batchTotal, salvaged.size(), retry + 1);
                return salvaged;
            } else {
                int next = Math.min(maxTokens * 2, MAX_REDUCE_TOKENS_HARD_CAP);
                logger.warn("Reduce 批次 {}/{} 输出被截断且无可抢救页面（第 {} 次），max_tokens {}→{}",
                        batchNo, batchTotal, retry + 1, maxTokens, next);
                maxTokens = next;
            }
        }
        logger.error("Reduce 批次 {}/{} 在 {} 次尝试后仍失败", batchNo, batchTotal, MAX_REDUCE_RETRIES);
        return Collections.emptyList();
    }

    private String buildReducePrompt(List<PartialWikiEntry> batch, String fileName, List<TextChunk> chunks) {
        StringBuilder entriesText = new StringBuilder();
        int[] evidenceBudget = {EVIDENCE_TOTAL_CHARS};
        for (int i = 0; i < batch.size(); i++) {
            PartialWikiEntry e = batch.get(i);
            entriesText.append("--- 条目 ").append(i + 1).append(" ---\n");
            entriesText.append("名称：").append(e.getName()).append("\n");
            entriesText.append("定义：").append(e.getDefinition()).append("\n");
            entriesText.append("关键信息：").append(String.join("；", e.getKeyInfo())).append("\n");
            entriesText.append("关联概念：").append(String.join(", ", e.getRelatedConcepts())).append("\n");
            if (e.getAliases() != null && !e.getAliases().isEmpty()) {
                entriesText.append("别名：").append(String.join(", ", e.getAliases())).append("\n");
            }
            entriesText.append("来源：").append(e.getSourceChunkId()).append("\n");
            String evidence = buildEvidence(e.getName(), chunks, evidenceBudget);
            if (!evidence.isEmpty()) {
                entriesText.append("原文证据（必须据此撰写，不得编造）：").append(evidence).append("\n");
            }
            entriesText.append("\n");
        }

        return String.format("""
                合并以下从同一文档不同部分提取的概念条目，生成信息充分的标准 Wiki 页面。

                每个概念页面必须使用统一的 Markdown 模板：
                # 概念名
                ## 一句话定义
                （一句话，20-60 字）
                ## 核心原理 / 机制
                （2-4 段，说明它是什么、如何运作、为什么这样设计）
                ## 关键要素
                （至少 4 条要点，尽量包含具体事实、数字、参数、条件或名词解释）
                ## 典型应用场景
                （至少 2 条）
                ## 优缺点 / 权衡
                （优点与局限，各 1-2 条；无相关信息可省略本段）
                ## 关联概念
                [[概念A]], [[概念B]]
                ## 参考来源
                - <来源文件名> <chunk_编号>
                别名: 别名1, 别名2
                （仅当该条目提供了别名时输出最后一行，否则省略）

                硬性规则：
                1. 内容必须严格来自「原文证据」与条目信息，禁止编造或引入外部知识
                2. 若某段落证据不足，写“片段未覆盖”，不要为了凑长度而编造
                3. 引用其他概念时用 [[概念名]] 标记（含关联概念段）
                4. 相同概念合并为一个页面，概念名作为 # 标题
                5. 每页尽量充实完整，但准确性优先于篇幅
                6. 只输出本批条目对应的页面，控制在预算长度内，优先保证每页结构完整可解析

                输出格式（JSON 数组）：
                [
                  {
                    "title": "概念名称（作为 # 标题）",
                    "markdown": "完整的 Markdown 内容"
                  }
                ]

                来源文件：%s

                待合并的条目：
                %s
                """, fileName, entriesText);
    }

    /**
     * 截断容错解析：从（可能被 max_tokens 截断的）JSON 数组文本里，按花括号配平逐个抽取
     * 已完整的顶层对象并解析，忽略末尾被切断的半个对象。即使输出超限也不会颗粒无收。
     */
    private List<Map<String, Object>> parsePagesTolerant(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        String cleaned = text.replaceAll("```[a-zA-Z]*", "");
        int start = cleaned.indexOf('[');
        if (start < 0) {
            start = cleaned.indexOf('{');
        }
        if (start < 0) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        int objStart = -1;
        for (int i = start; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    objStart = i;
                }
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String objText = cleaned.substring(objStart, i + 1);
                        try {
                            Map<String, Object> obj = objectMapper.readValue(objText,
                                    new TypeReference<Map<String, Object>>() {});
                            if (!safeString(obj.get("title")).isEmpty()
                                    && !safeString(obj.get("markdown")).isEmpty()) {
                                result.add(obj);
                            }
                        } catch (Exception ignored) {
                            // 单个对象解析失败则跳过，继续扫描后续完整对象
                        }
                        objStart = -1;
                    }
                }
            }
        }
        return result;
    }

    private static final int EVIDENCE_CHUNKS_PER_CONCEPT = 3;
    private static final int EVIDENCE_CHUNK_CHARS = 1000;
    private static final int EVIDENCE_TOTAL_CHARS = 50000;

    /**
     * 为单个概念挑选"提及它的原始 chunk"作为撰写证据（每概念最多 3 段、每段截断 1000 字、全局预算 5 万字）。
     * 方案 B 的关键：Reduce 只看提炼条目会"越合越薄"，注入原文才能写出充实页面，且不增加 LLM 调用次数。
     */
    String buildEvidence(String conceptName, List<TextChunk> chunks, int[] remainingChars) {
        if (conceptName == null || conceptName.isBlank() || chunks == null
                || chunks.isEmpty() || remainingChars[0] <= 0) {
            return "";
        }
        String needle = conceptName.toLowerCase(Locale.ROOT);
        StringBuilder evidence = new StringBuilder();
        int usedChunks = 0;
        for (TextChunk chunk : chunks) {
            if (usedChunks >= EVIDENCE_CHUNKS_PER_CONCEPT || remainingChars[0] <= 0) {
                break;
            }
            String text = chunk.getContent() == null ? "" : chunk.getContent();
            if (!text.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            String clipped = text.length() <= EVIDENCE_CHUNK_CHARS
                    ? text
                    : text.substring(0, EVIDENCE_CHUNK_CHARS) + "...";
            evidence.append("\n    [chunk_").append(chunk.getChunkId()).append("] ").append(clipped);
            usedChunks++;
            remainingChars[0] -= clipped.length();
        }
        return evidence.toString();
    }

    // ---- 工具方法 ----

    private List<TextChunk> fetchChunks(String fileMd5) {
        var vectors = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5);
        return vectors.stream()
                .map(v -> new TextChunk(v.getChunkId(), v.getTextContent(),
                        v.getPageNumber(), v.getAnchorText()))
                .collect(Collectors.toList());
    }

/**
     * 从 LLM 输出中提取 JSON 数组（兼容可能包含 代码块标记）
 */
private String extractJsonArray(String text) {
    if (text == null) return null; // 先尝试去掉代码块标记
    //String cleaned = text.replaceAll("[a-zA-Z]*", "").trim();
    String cleaned = text.replaceAll("```[a-zA-Z]*", "").trim();
    int start = cleaned.indexOf('[');
    int end = cleaned.lastIndexOf(']');
    if (start >= 0 && end > start) {
        return cleaned.substring(start, end + 1);
    }
    return null;
}

    private String safeString(Object obj) {
        return obj == null ? "" : obj.toString().trim();
    }

    private List<String> safeList(Object obj) {
        if (obj instanceof List) {
            return ((List<?>) obj).stream()
                    .map(this::safeString)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
