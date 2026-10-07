package com.mindflow.module.chat.agent;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.DocStats;
import co.elastic.clients.elasticsearch._types.StoreStats;
import co.elastic.clients.elasticsearch.indices.IndicesStatsResponse;
import co.elastic.clients.elasticsearch.indices.stats.IndicesStats;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.client.DeepSeekClient;
import com.mindflow.harness.mcp.DefaultMcpToolRegistry;
import com.mindflow.harness.skill.Skill;
import com.mindflow.harness.skill.SkillContextBuffer;
import com.mindflow.harness.skill.SkillRegistry;
import com.mindflow.module.memory.service.LongTermMemoryService;
import com.mindflow.module.skill.entity.SkillFileEntry;
import com.mindflow.module.skill.entity.SkillPackage;
import com.mindflow.module.skill.service.SkillPackageService;
import com.mindflow.module.search.dto.SearchResult;
import com.mindflow.module.document.entity.FileUpload;
import com.mindflow.module.document.repository.FileUploadRepository;
import com.mindflow.module.search.service.HybridSearchService;
import com.mindflow.module.search.service.RerankService;
import com.mindflow.module.wiki.service.WikiSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class AgentToolRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AgentToolRegistry.class);

    private static final String KNOWLEDGE_INDEX = "knowledge_base";
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_SEARCH_DOCS = 20;

    private final HybridSearchService hybridSearchService;
    private final DeepSeekClient deepSeekClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ElasticsearchClient elasticsearchClient;
    private final FileUploadRepository fileUploadRepository;
    private final List<AgentTool> tools;
    private final Map<String, ToolHandler> handlers;
    /** 精排扩展点：见 executeSearchKnowledge 处的说明，当前默认不接入主链路 */
    private final RerankService rerankService;
    private final WikiSearchService wikiSearchService;  // 新增
    private final LongTermMemoryService longTermMemoryService;
    private final DefaultMcpToolRegistry mcpToolRegistry;
    private final SkillRegistry skillRegistry;
    private final SkillContextBuffer skillContextBuffer;
    private final SkillPackageService skillPackageService;
    private final LocalFileTools localFileTools;
    private final com.mindflow.module.chat.repository.MessageFeedbackRepository messageFeedbackRepository;
    private final ObjectMapper objectMapper;
    public AgentToolRegistry(HybridSearchService hybridSearchService,
                             DeepSeekClient deepSeekClient,
                             StringRedisTemplate stringRedisTemplate,
                             ElasticsearchClient elasticsearchClient,
                             FileUploadRepository fileUploadRepository,
                             RerankService rerankService,
                             WikiSearchService wikiSearchService,
                             LongTermMemoryService longTermMemoryService,
                             DefaultMcpToolRegistry mcpToolRegistry,
                             SkillRegistry skillRegistry,
                             SkillContextBuffer skillContextBuffer,
                             SkillPackageService skillPackageService,
                             LocalFileTools localFileTools,
                             com.mindflow.module.chat.repository.MessageFeedbackRepository messageFeedbackRepository,
                             ObjectMapper objectMapper) {
        this.hybridSearchService = hybridSearchService;
        this.deepSeekClient = deepSeekClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.elasticsearchClient = elasticsearchClient;
        this.fileUploadRepository = fileUploadRepository;
        this.rerankService = rerankService;
        this.wikiSearchService = wikiSearchService;  // 新增
        this.longTermMemoryService = longTermMemoryService;
        this.mcpToolRegistry = mcpToolRegistry;
        this.skillRegistry = skillRegistry;
        this.skillContextBuffer = skillContextBuffer;
        this.skillPackageService = skillPackageService;
        this.localFileTools = localFileTools;
        this.messageFeedbackRepository = messageFeedbackRepository;
        this.objectMapper = objectMapper;
        List<AgentTool> builtin = new ArrayList<>(List.of(
                searchKnowledgeTool(),
                generateSummaryTool(),
                submitFeedbackTool(),
                knowledgeStatsTool(),
                searchWikiTool(),
                navigateWikiTool(),
                saveMemoryTool(),
                loadSkillTool()
        ));
        builtin.addAll(localFileTools.definitions());
        this.tools = List.copyOf(builtin);
        this.handlers = Map.of(
                "search_knowledge", this::executeSearchKnowledge,
                "generate_summary", this::executeGenerateSummary,
                "submit_feedback", this::executeSubmitFeedback,
                "knowledge_stats", this::executeKnowledgeStats,
                "search_wiki", this::executeSearchWiki,
                "navigate_wiki", this::executeNavigateWiki,
                "save_memory", this::executeSaveMemory,
                "load_skill", this::executeLoadSkill
        );

    }

    /**
     * 仅内置（受信）工具，不含动态注册的 MCP 工具——工具目录页用它区分左右两栏。
     */
    public List<AgentTool> builtinTools() {
        return tools;
    }

    public List<AgentTool> getTools() {        List<AgentTool> dynamic = mcpToolRegistry.getToolDefinitions().stream()
                .map(this::toAgentTool)
                .toList();
        if (dynamic.isEmpty()) {
            return tools;
        }
        List<AgentTool> combined = new java.util.ArrayList<>(tools);
        combined.addAll(dynamic);
        return List.copyOf(combined);
    }

    private AgentTool toAgentTool(com.mindflow.harness.mcp.protocol.McpToolDescriptor descriptor) {
        Map<String, Object> parameters;
        try {
            JsonNode schema = descriptor.inputSchema();
            @SuppressWarnings("unchecked")
            Map<String, Object> converted = schema == null
                    ? null
                    : objectMapper.convertValue(schema, Map.class);
            parameters = converted != null ? converted : fallbackObjectSchema();
        } catch (Exception e) {
            parameters = fallbackObjectSchema();
        }
        return new AgentTool(descriptor.namespacedName(), descriptor.description(), parameters);
    }

    private Map<String, Object> fallbackObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.put("required", List.of());
        return schema;
    }

    public Optional<AgentTool> getTool(String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst();
    }

    public ToolExecutionResult executeTool(String name, Map<String, Object> arguments, String userId) {
        return executeTool(name, arguments, userId, null);
    }

    public ToolExecutionResult executeTool(String name,
                                           Map<String, Object> arguments,
                                           String userId,
                                           Consumer<String> onChunk) {
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            if (localFileTools.handles(name)) {
                return localFileTools.execute(name, arguments);
            }
            if (mcpToolRegistry.hasTool(name)) {
                String argumentsJson;
                try {
                    argumentsJson = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
                } catch (Exception e) {
                    argumentsJson = "{}";
                }
                String content = mcpToolRegistry.executeTool(name, argumentsJson);
                boolean success = !content.startsWith("MCP 工具执行失败: ")
                        && !content.startsWith("未知 MCP 工具: ");
                return new ToolExecutionResult(name, success, content, Map.of());
            }
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        return handler.execute(arguments == null ? Collections.emptyMap() : arguments, userId, onChunk);
    }

    private ToolExecutionResult executeSearchKnowledge(Map<String, Object> arguments,
                                                       String userId,
                                                       Consumer<String> onChunk) {
        requireUserId(userId);
        String query = getRequiredString(arguments, "query");
        int topK = getInt(arguments, "topK", DEFAULT_TOP_K, 1, MAX_SEARCH_DOCS);
        // 双路召回 + RRF 融合：KNN(50) 与 BM25(50) 并行召回 → RRF(k=60) 融合取前 20 → 按 topK 截断交给 LLM。
        // LLM 精排（rerankService.rerank）是已实现但未默认接入的扩展点：对 20 条候选再走一轮打分
        // 需额外一次 LLM 调用与配额消耗，当前知识库规模下收益低于延迟成本，需要时在此行后接一次即可。
        List<SearchResult> results = hybridSearchService.searchWithRrfRerank(query,userId, 50, 50, 20);
        //List<SearchResult> results = hybridSearchService.searchWithPermission(query, userId, topK);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("topK", topK);
        data.put("results", results);
        return new ToolExecutionResult("search_knowledge", true, formatSearchResults(results), data);
    }

    private ToolExecutionResult executeGenerateSummary(Map<String, Object> arguments,
                                                       String userId,
                                                       Consumer<String> onChunk) {
        requireUserId(userId);
        String topic = getRequiredString(arguments, "topic");
        int maxDocs = getInt(arguments, "maxDocs", DEFAULT_TOP_K, 1, MAX_SEARCH_DOCS);

        List<SearchResult> results = hybridSearchService.searchWithPermission(topic, userId, maxDocs);
        String summary = deepSeekClient.summarize(userId, topic, results, onChunk);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("maxDocs", maxDocs);
        data.put("sourceCount", results.size());
        data.put("sources", results);

        String content = "主题：" + topic + "\n"
                + "检索片段数：" + results.size() + "\n\n"
                + summary;
        return new ToolExecutionResult("generate_summary", true, content, data, onChunk != null);
    }

    private ToolExecutionResult executeSubmitFeedback(Map<String, Object> arguments,
                                                      String userId,
                                                      Consumer<String> onChunk) {
        requireUserId(userId);
        String rating = getRequiredString(arguments, "rating").toLowerCase(Locale.ROOT);
        if (!"good".equals(rating) && !"bad".equals(rating)) {
            throw new IllegalArgumentException("rating 只允许 good 或 bad");
        }
        String reason = getOptionalString(arguments, "reason");
        String key = "feedback:" + userId;
        String field = String.valueOf(System.currentTimeMillis());
        String value = reason == null || reason.isBlank()
                ? "rating=" + rating
                : "rating=" + rating + "; reason=" + reason;
        stringRedisTemplate.opsForHash().put(key, field, value);

        // 与前端点赞走同一个持久化表：两条入口归一到单一数据源，供 buildRecentFeedbackGuidance 读取
        try {
            com.mindflow.module.chat.entity.MessageFeedback feedback =
                    new com.mindflow.module.chat.entity.MessageFeedback();
            feedback.setUserId(Long.parseLong(userId));
            feedback.setRating(rating);
            feedback.setReason(reason);
            feedback.setAnswerExcerpt(reason);
            messageFeedbackRepository.save(feedback);
        } catch (Exception e) {
            logger.warn("反馈落库失败（Redis 已记录）: userId={}, error={}", userId, e.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("field", field);
        data.put("rating", rating);
        data.put("reason", reason);
        return new ToolExecutionResult("submit_feedback", true, "已记录用户反馈: " + value, data);
    }

    private ToolExecutionResult executeKnowledgeStats(Map<String, Object> arguments,
                                                      String userId,
                                                      Consumer<String> onChunk) {
        try {
            IndicesStatsResponse statsResponse = elasticsearchClient.indices().stats(s -> s.index(KNOWLEDGE_INDEX));
            IndicesStats indexStats = statsResponse.indices().get(KNOWLEDGE_INDEX);
            DocStats docStats = indexStats != null && indexStats.total() != null ? indexStats.total().docs() : null;
            StoreStats storeStats = indexStats != null && indexStats.total() != null ? indexStats.total().store() : null;

            long documentCount = fileUploadRepository.count();
            long fragmentCount = docStats != null ? docStats.count() : 0L;
            Long deletedFragmentCount = docStats != null ? docStats.deleted() : null;
            Long storeSizeInBytes = storeStats != null ? storeStats.sizeInBytes() : null;
            LocalDateTime latestUpdatedAt = fileUploadRepository.findFirstByOrderByMergedAtDesc()
                    .map(this::resolveLatestUpdatedAt)
                    .orElse(null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("index", KNOWLEDGE_INDEX);
            data.put("documentCount", documentCount);
            data.put("fragmentCount", fragmentCount);
            data.put("deletedFragmentCount", deletedFragmentCount);
            data.put("storeSizeInBytes", storeSizeInBytes);
            data.put("latestUpdatedAt", latestUpdatedAt);

            return new ToolExecutionResult("knowledge_stats", true, formatKnowledgeStats(data), data);
        } catch (Exception e) {
            throw new RuntimeException("获取知识库统计信息失败", e);
        }
    }

    private AgentTool searchKnowledgeTool() {
        return new AgentTool(
                "search_knowledge",
                "在知识库中搜索与用户问题相关的文档片段。当用户问题的答案可能依赖已上传资料、企业/项目/产品/系统内部信息、专有名词、事实依据、定义、功能、使用方式、实现细节、背景、流程或引用来源时应调用；即使用户没有明确说“查询知识库”，只要问题不像纯通用常识也应先检索。普通问候、闲聊、纯创作、翻译、通用代码/常识问题，或用户明确要求不要查知识库时不要调用。",
                objectSchema(Map.of(
                        "query", stringSchema("用于知识库检索的查询语句。应保留用户原话中的核心实体、缩写和限定词，可包含原始问句和必要的等价改写；不要替换成固定关键词。"),
                        "topK", integerSchema("返回的片段数量，默认 5。")
                ), List.of("query"))
        );
    }

    private AgentTool generateSummaryTool() {
        return new AgentTool(
                "generate_summary",
                "对指定主题的知识库文档生成结构化摘要。适合用户要求整理、总结、归纳、提炼知识库内容时调用；本工具内部会二次调用大模型完成摘要，外层 ReAct 循环只应接收结果，不要把内部摘要过程当作新的工具计划。",
                objectSchema(Map.of(
                        "topic", stringSchema("需要从知识库中整理和总结的主题。"),
                        "maxDocs", integerSchema("用于生成摘要的最多相关片段数量，默认 5。")
                ), List.of("topic"))
        );
    }

    private AgentTool submitFeedbackTool() {
        Map<String, Object> ratingSchema = stringSchema("用户对当前回答的评价，只能是 good 或 bad。");
        ratingSchema.put("enum", List.of("good", "bad"));
        return new AgentTool(
                "submit_feedback",
                "当用户明确表达对回答满意、不满意、点赞、点踩、纠错或要求记录反馈时调用，用于记录反馈以优化后续回答质量；不要在没有明确评价意图时推断调用。",
                objectSchema(Map.of(
                        "rating", ratingSchema,
                        "reason", stringSchema("用户给出的满意或不满意原因，可为空。")
                ), List.of("rating"))
        );
    }

    private AgentTool knowledgeStatsTool() {
        return new AgentTool(
                "knowledge_stats",
                "返回当前知识库的统计信息，包括 MySQL 文档总数、Elasticsearch 片段总数、索引存储量和最近更新时间。仅当用户询问知识库规模、文档数量、片段数量、更新时间或索引状态时调用。",
                objectSchema(Collections.emptyMap(), Collections.emptyList())
        );
    }

    private AgentTool saveMemoryTool() {
        return new AgentTool(
                "save_memory",
                "在用户明确要求\"记一下/记住/以后记得\"或表达长期偏好（如保存常用设置、记录稳定事实）时调用，保存跨会话仍成立的精炼事实。" +
                        "不要保存一次性任务请求、临时文件名、模型猜测或当前轮执行计划；只保存用户明确表达的稳定信息。",
                objectSchema(Map.of(
                        "fact", stringSchema("要保存的精炼稳定事实，一条一个事实，控制在 500 字以内。")
                ), List.of("fact"))
        );
    }

    private ToolExecutionResult executeSaveMemory(Map<String, Object> arguments,
                                                  String userId,
                                                  Consumer<String> onChunk) {
        requireUserId(userId);
        String fact = getRequiredString(arguments, "fact");
        boolean saved = longTermMemoryService.save(userId, fact);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fact", fact);
        data.put("saved", saved);
        return new ToolExecutionResult("save_memory", true,
                saved ? "已保存长期记忆：" + fact : "该长期记忆已存在，无需重复保存。",
                data);
    }

    private AgentTool loadSkillTool() {
        return new AgentTool(
                "load_skill",
                "当 system prompt 的「可用 Skills」索引段中出现与当前任务匹配的能力手册时调用，加载该 Skill 的入口文档 SKILL.md。" +
                        "返回内容包含正文（按行分页）与包内文件清单；加载后的正文会在下一轮对话上下文中生效。" +
                        "附属文件（references/ 等）不要在这里一次读完：先按需调用 read_file 只读当前需要的那一个。" +
                        "正文过长时用 offset/limit 续读。不要无差别加载不相关的 Skill。",
                objectSchema(Map.of(
                        "name", stringSchema("要加载的 Skill 名称，必须与索引段中列出的名称完全一致。"),
                        "offset", integerSchema("入口文档起始行号，从 1 开始，默认 1；用于续读被分页的 SKILL.md。"),
                        "limit", integerSchema("本次最多返回行数，默认 600，上限 2000。")
                ), List.of("name"))
        );
    }

    private ToolExecutionResult executeLoadSkill(Map<String, Object> arguments,
                                                 String userId,
                                                 Consumer<String> onChunk) {
        requireUserId(userId);
        String name = getRequiredString(arguments, "name");
        int offset = getInt(arguments, "offset", 1, 1, 1_000_000);
        int limit = getInt(arguments, "limit", 0, 0, 2000);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);

        // 优先走目录包：读磁盘 SKILL.md（分页）+ 文件清单，让第三级披露（read_file 按需读附属文件）有锚点
        Optional<SkillPackage> packageOpt = safeFindPackage(name);
        if (packageOpt.isPresent()) {
            SkillPackage pkg = packageOpt.get();
            SkillPackageService.EntryText entry = safeReadEntry(pkg, offset, limit);
            if (entry != null) {
                String bufferBody = skillPackageService.buildBufferBody(pkg, entry);
                skillContextBuffer.offer(userId, name, bufferBody);

                StringBuilder content = new StringBuilder();
                content.append("已加载 Skill「").append(name).append("」：")
                        .append(entry.path());
                if (entry.totalLines() > 0) {
                    content.append("（共 ").append(entry.totalLines()).append(" 行，本次第 ")
                            .append(entry.from()).append('-').append(entry.to()).append(" 行）");
                }
                content.append("\n\n").append(entry.content());
                if (entry.truncated()) {
                    content.append("\n\n（入口文档未完，可用 read_file 继续：path=").append(entry.path())
                            .append(", offset=").append(entry.to() + 1).append("）");
                }
                appendFileManifest(content, pkg);
                appendDegradedNotice(content, pkg);
                content.append("\n\n正文将在下一轮对话上下文中生效。");

                data.put("loaded", true);
                data.put("entryPath", entry.path());
                data.put("fileCount", pkg.getFileCount());
                data.put("status", pkg.getStatus());
                return new ToolExecutionResult("load_skill", true, content.toString(), data);
            }
            // 有包但入口不可读：不直接失败，回落到旧口径正文，只多给一句提示
            logger.warn("Skill 包入口文件不可读，回落旧数据: name={}, entryPath={}", name, pkg.getEntryPath());
        }

        Optional<Skill> skill = skillRegistry.get(name);
        if (skill.isEmpty()) {
            data.put("loaded", false);
            String available = skillRegistry.enabledSkills().stream()
                    .map(Skill::getName)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("（无）");
            return new ToolExecutionResult("load_skill", false,
                    "未找到 Skill「" + name + "」。当前可用: " + available, data);
        }

        String body = skill.get().getBody() == null ? "" : skill.get().getBody();
        boolean bodyTruncated = body.length() > 5000;
        if (bodyTruncated) {
            body = body.substring(0, 5000) + "\n...[已截断]";
        }
        skillContextBuffer.offer(userId, name, body);
        data.put("loaded", true);
        String legacyContent = "已加载 Skill「" + name + "」，其决策手册将在下一轮对话上下文中生效。"
                + (bodyTruncated ? "（正文较长已截断，建议重新以目录形式导入以获得完整内容与附属文件）" : "");
        return new ToolExecutionResult("load_skill", true, legacyContent, data);
    }

    private Optional<SkillPackage> safeFindPackage(String name) {
        try {
            return skillPackageService.find(name);
        } catch (Exception e) {
            logger.warn("查询 Skill 包失败，回落旧数据: name={}, error={}", name, e.getMessage());
            return Optional.empty();
        }
    }

    private SkillPackageService.EntryText safeReadEntry(SkillPackage pkg, int offset, int limit) {
        try {
            return skillPackageService.readEntry(pkg.getName(), offset, limit);
        } catch (RuntimeException e) {
            logger.warn("读取 Skill 入口文件失败: name={}, error={}", pkg.getName(), e.getMessage());
            return null;
        }
    }

    /** 把包内文件清单回灌给模型：它只需要知道“能读哪几个”，具体读哪个自己决定 */
    private void appendFileManifest(StringBuilder content, SkillPackage pkg) {
        String skillName = pkg.getName();
        // 用实体里的真实目录路径，而不是写死 data/skills——skill.storage.path 可被配置覆盖
        String dir = pkg.getDirPath() == null || pkg.getDirPath().isBlank()
                ? "data/skills/" + skillName : pkg.getDirPath();
        List<SkillFileEntry> files;
        try {
            files = skillPackageService.listFiles(skillName);
        } catch (Exception e) {
            logger.warn("读取 Skill 文件清单失败: name={}, error={}", skillName, e.getMessage());
            return;
        }
        if (files.isEmpty()) {
            return;
        }
        content.append("\n\n【包内文件清单】共 ").append(files.size()).append(" 个，目录 ").append(dir).append("/");
        for (SkillFileEntry file : files) {
            content.append("\n- ").append(file.getRelPath())
                    .append(" (").append(humanSize(file.getSizeBytes())).append(")")
                    .append(Boolean.TRUE.equals(file.getIsEntry()) ? " ←入口" : "")
                    .append(Boolean.TRUE.equals(file.getTextReadable())
                            ? "" : " ←二进制，仅登记路径不可读");
        }
        content.append("\n（需要某个附属文件时，用 read_file 读取 ").append(dir)
                .append("/<相对路径>；只读当前需要的那一个，不要把整个目录读进上下文）");
    }

    private void appendDegradedNotice(StringBuilder content, SkillPackage pkg) {
        boolean degraded = SkillPackage.STATUS_DEGRADED.equalsIgnoreCase(pkg.getStatus());
        String deps = pkg.getMissingDeps();
        if (degraded && deps != null && !deps.isBlank() && !"[]".equals(deps)) {
            content.append("\n\n⚠ 该 Skill 依赖未完全满足：")
                    .append(deps.replaceAll("[\"\\[\\]]", ""))
                    .append("\n遇到不具备的能力时，请直接向用户说明限制，不要反复尝试同一个调用。");
        }
    }

    private static String humanSize(Long bytes) {
        if (bytes == null || bytes < 0) {
            return "未知大小";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
    }

    // ====== 新增：Wiki 搜索工具 ======

    private AgentTool searchWikiTool() {
        return new AgentTool(
                "search_wiki",
                "在 Wiki 知识库中搜索结构化概念页面。Wiki 页面是由已上传文档自动生成的 Markdown 知识卡片，包含概念定义、关键信息和关联链接。" +
                        "当用户询问概念定义、术语解释、实体关系、知识概述时调用，例如'什么是向量数据库'、'RAG 有哪些组件'、'解释一下 ANN 检索'。" +
                        "搜索返回的结构化页面可以作为回答的核心依据，如需更具体的事实数据再配合 search_knowledge 工具。",
                objectSchema(Map.of(
                        "query", stringSchema("搜索关键词，应包含用户问题中的核心概念名称或术语，越具体越好。"),
                        "topK", integerSchema("返回的 Wiki 页面数量，默认 3。")
                ), List.of("query"))
        );
    }

    private AgentTool navigateWikiTool() {
        return new AgentTool(
                "navigate_wiki",
                "按概念名称跳转到指定 Wiki 页面，返回页面完整内容、其关联概念（[[双向链接]] 指向的其他页面）以及反向链接。" +
                        "支持多跳推理：hops=1（默认）返回一跳关联页内容，hops=2 再展开一跳，用于沿知识图谱深入探索（如 CAP原则 → CA/CP/AP模型 → BASE理论）。" +
                        "适合在 search_wiki 或上下文中已确认概念名后使用；名称不确定时先用 search_wiki 模糊检索。",
                objectSchema(Map.of(
                        "target_concept", stringSchema("要导航到的 Wiki 页面标题（如 'CAP原则'），大小写不敏感。"),
                        "hops", integerSchema("沿关联概念正向展开的跳数，1 或 2，默认 1。")
                ), List.of("target_concept"))
        );
    }

    private ToolExecutionResult executeNavigateWiki(Map<String, Object> arguments,
                                                    String userId,
                                                    Consumer<String> onChunk) {
        String targetConcept = getRequiredString(arguments, "target_concept");
        int hops = getInt(arguments, "hops", 1, 1, 2);

        var result = wikiSearchService.navigateWithHops(targetConcept, hops);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("target_concept", targetConcept);
        data.put("hops", hops);
        if (result.isEmpty()) {
            data.put("found", false);
            return new ToolExecutionResult("navigate_wiki", true,
                    "未找到名为「" + targetConcept + "」的 Wiki 页面。请改用 search_wiki 模糊检索相似概念。",
                    data);
        }
        var page = result.get();
        data.put("found", true);
        data.put("title", page.title());
        data.put("backlinks", page.backlinks());
        data.put("relatedCount", page.related().size());

        StringBuilder content = new StringBuilder("Wiki 页面「").append(page.title()).append("」内容：\n\n")
                .append(page.markdown());
        if (!page.related().isEmpty()) {
            content.append("\n\n【关联概念（已按 ").append(hops).append(" 跳展开）】");
            for (var related : page.related()) {
                content.append("\n\n--- 关联页：").append(related.title()).append(" ---\n")
                        .append(related.markdown());
            }
            content.append("\n\n如需继续深入，可对上述任一关联页再次调用 navigate_wiki（hops 可设 2）。");
        }
        if (!page.backlinks().isEmpty()) {
            content.append("\n\n反向链接（引用了此页面的页面）：");
            for (String source : page.backlinks()) {
                content.append("\n- [[").append(source).append("]]");
            }
        }
        return new ToolExecutionResult("navigate_wiki", true, content.toString(), data);
    }

    private ToolExecutionResult executeSearchWiki(Map<String, Object> arguments,
                                                  String userId,
                                                  Consumer<String> onChunk) {
        String query = getRequiredString(arguments, "query");
        int topK = getInt(arguments, "topK", 3, 1, 10);

        List<WikiSearchService.WikiSearchResult> results = wikiSearchService.search(query, topK);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("topK", topK);
        data.put("pageCount", results.size());

        return new ToolExecutionResult("search_wiki", true, formatWikiResults(results), data);
    }

    private String formatWikiResults(List<WikiSearchService.WikiSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "未在 Wiki 知识库中找到相关概念页面。";
        }

        StringBuilder output = new StringBuilder("在 Wiki 知识库中找到 ")
                .append(results.size()).append(" 个相关概念页面。")
                .append("请优先引用这些结构化信息回答用户问题。");

        for (int i = 0; i < results.size(); i++) {
            WikiSearchService.WikiSearchResult r = results.get(i);
            output.append("\n\n--- Wiki 页面 ").append(i + 1).append(" ---\n");
            output.append(r.markdown());
            List<String> related = wikiSearchService.relatedConcepts(r.title());
            if (!related.isEmpty()) {
                output.append("\n（关联概念：").append(String.join("、", related))
                        .append("。如需继续深入，可用 navigate_wiki 跳转到其中任一概念）");
            }
        }
        return output.toString();
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> stringSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> integerSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    private String formatSearchResults(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "未检索到相关知识库片段。";
        }

        StringBuilder output = new StringBuilder("检索到 ").append(results.size()).append(" 个知识库片段。")
                .append("请基于这些片段回答用户问题；不得声称知识库暂无相关信息。")
                .append("如果片段信息不足，请说明“基于已检索片段只能确认……”并标注来源编号。");
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            output.append("\n\n[").append(i + 1).append("] ");
            if (result.getFileName() != null && !result.getFileName().isBlank()) {
                output.append(result.getFileName()).append(" ");
            }
            output.append("(fileMd5=").append(result.getFileMd5())
                    .append(", chunkId=").append(result.getChunkId());
            if (result.getPageNumber() != null) {
                output.append(", page=").append(result.getPageNumber());
            }
            if (result.getScore() != null) {
                output.append(", score=").append(String.format(Locale.ROOT, "%.4f", result.getScore()));
            }
            output.append(")\n")
                    .append(limitText(result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent(), 1200));
        }
        return output.toString();
    }

    private String formatKnowledgeStats(Map<String, Object> data) {
        return "知识库统计："
                + "\n- MySQL 文档总数：" + data.get("documentCount")
                + "\n- Elasticsearch 片段总数：" + data.get("fragmentCount")
                + "\n- ES 已删除片段数：" + nullToDash(data.get("deletedFragmentCount"))
                + "\n- ES 存储大小(bytes)：" + nullToDash(data.get("storeSizeInBytes"))
                + "\n- 最近更新时间：" + nullToDash(data.get("latestUpdatedAt"));
    }

    private LocalDateTime resolveLatestUpdatedAt(FileUpload fileUpload) {
        if (fileUpload.getMergedAt() != null) {
            return fileUpload.getMergedAt();
        }
        return fileUpload.getCreatedAt();
    }

    private String getRequiredString(Map<String, Object> arguments, String name) {
        String value = getOptionalString(arguments, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private String getOptionalString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private int getInt(Map<String, Object> arguments, String name, int defaultValue, int min, int max) {
        Object raw = arguments.get(name);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return defaultValue;
        }

        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            value = Integer.parseInt(String.valueOf(raw));
        }
        return Math.max(min, Math.min(max, value));
    }

    private String limitText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private String nullToDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("工具调用缺少 userId，无法执行带权限的知识库或反馈操作");
        }
    }

    @FunctionalInterface
    private interface ToolHandler {
        ToolExecutionResult execute(Map<String, Object> arguments, String userId, Consumer<String> onChunk);
    }

    public record AgentTool(
            String name,
            String description,
            Map<String, Object> parameters
    ) {
    }

    public record ToolExecutionResult(
            String toolName,
            boolean success,
            String content,
            Map<String, Object> data,
            boolean streamedToUser
    ) {
        public ToolExecutionResult(String toolName,
                                   boolean success,
                                   String content,
                                   Map<String, Object> data) {
            this(toolName, success, content, data, false);
        }
    }
}
