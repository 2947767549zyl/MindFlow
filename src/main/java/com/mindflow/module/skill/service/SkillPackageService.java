package com.mindflow.module.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.common.exception.CustomException;
import com.mindflow.harness.skill.Skill;
import com.mindflow.harness.skill.SkillFrontmatterParser;
import com.mindflow.harness.skill.SkillStateStore;
import com.mindflow.module.chat.agent.AgentToolRegistry;
import com.mindflow.module.skill.entity.SkillFileEntry;
import com.mindflow.module.skill.entity.SkillPackage;
import com.mindflow.module.skill.repository.SkillFileEntryRepository;
import com.mindflow.module.skill.repository.SkillPackageRepository;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Skill 包存储服务：把"一个 skill 目录"当作最小导入单元。
 *
 * 双源结构与 wiki 一致——
 * - 磁盘 data/skills/&lt;name&gt;/：原样保留目录树，是 Agent 三级披露的读取入口
 *   （read_file/glob 的围栏根就是 user.dir，data/ 天然在围栏内）；
 * - MySQL skill_packages / skill_files：权威清单与元数据，Redis 只当提示词缓存。
 *
 * 依赖校验在导入期完成（对应"导入期依赖解析与降级提示"规范）：SKILL.md 里引用的相对路径
 * 是否随包上传、引用的工具名是否已在 AgentToolRegistry 注册、是否有脚本执行诉求，
 * 结论落到 status/missing_deps，供索引段与 load_skill 明确降级说明，而不是让模型撞墙。
 */
@Service
public class SkillPackageService {

    private static final Logger logger = LoggerFactory.getLogger(SkillPackageService.class);

    /** 单文件上限：512KB（skill 附属文件应当是文档/小脚本，不是数据仓库） */
    private static final long MAX_FILE_BYTES = 512L * 1024L;
    /** 单包上限：5MB */
    private static final long MAX_PACKAGE_BYTES = 5L * 1024L * 1024L;
    private static final int MAX_FILE_COUNT = 200;

    /** load_skill 默认返回行数；超出部分由模型用 offset 继续读磁盘文件 */
    private static final int DEFAULT_ENTRY_LIMIT_LINES = 600;
    private static final int MAX_ENTRY_LIMIT_LINES = 2000;
    /** 注入 SkillContextBuffer 的正文字符上限：超过则带"用 read_file 续读"的可恢复提示 */
    private static final int BUFFER_BODY_CHARS = 16000;

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    /** 正文里引用的相对路径，例如 references/api.md、scripts/run.py */
    private static final Pattern REL_PATH_REF = Pattern.compile("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+\\.[A-Za-z0-9]{1,8}");
    /** 反引号内的工具名形态：snake_case 或 mcp__ 前缀 */
    private static final Pattern TOOL_TOKEN = Pattern.compile("`([a-z][a-z0-9_]{2,40})`|\\b(mcp__[A-Za-z0-9_.-]+)\\b");
    private static final Pattern EXEC_HINT = Pattern.compile(
            "(执行|运行|调用)[^。\n]{0,16}(脚本|命令)|[^a-z](bash|python|node|sh)\\s+\\S*\\.(sh|py|js)");

    private static final Set<String> SCRIPT_EXTENSIONS = Set.of("py", "sh", "bash", "js", "ts", "rb", "pl");
    private static final Set<String> CALL_CONTEXT_KEYWORDS = Set.of(
            "调用", "使用", "运行", "执行", "通过", "工具", "call", "use ", "run ");

    private static final Map<String, String> MEDIA_TYPES = Map.ofEntries(
            Map.entry("md", "text/markdown"),
            Map.entry("txt", "text/plain"),
            Map.entry("json", "application/json"),
            Map.entry("yaml", "application/x-yaml"),
            Map.entry("yml", "application/x-yaml"),
            Map.entry("csv", "text/csv"),
            Map.entry("py", "text/x-python"),
            Map.entry("sh", "text/x-shellscript"),
            Map.entry("js", "text/javascript"),
            Map.entry("sql", "text/x-sql"),
            Map.entry("html", "text/html"));

    private final SkillPackageRepository packageRepository;
    private final SkillFileEntryRepository fileRepository;
    private final SkillFrontmatterParser frontmatterParser;
    private final SkillStateStore skillStateStore;
    private final ObjectProvider<AgentToolRegistry> toolRegistryProvider;
    private final ObjectMapper objectMapper;

    @Value("${skill.storage.path:data/skills}")
    private String skillStoragePath;

    public SkillPackageService(SkillPackageRepository packageRepository,
                               SkillFileEntryRepository fileRepository,
                               SkillFrontmatterParser frontmatterParser,
                               SkillStateStore skillStateStore,
                               ObjectProvider<AgentToolRegistry> toolRegistryProvider,
                               ObjectMapper objectMapper) {
        this.packageRepository = packageRepository;
        this.fileRepository = fileRepository;
        this.frontmatterParser = frontmatterParser;
        this.skillStateStore = skillStateStore;
        this.toolRegistryProvider = toolRegistryProvider;
        this.objectMapper = objectMapper;
    }

    // ====== 对外数据结构 ======

    /** 一个待导入文件：relPath 为相对 skill 目录的路径（正斜杠） */
    public record PackageFile(String relPath, String originalName, byte[] content) {}

    /** 导入结果：warnings 是给操作员的提示，不影响导入成功 */
    public record ImportResult(SkillPackage skillPackage, List<String> warnings) {}

    /** 入口文档分页读取结果 */
    public record EntryText(String path, String content, int totalLines, int from, int to, boolean truncated) {}

    // ====== 导入 ======

    /**
     * 导入单个 SKILL.md（旧的 Web 入口，现在同样走落盘 + 入库，保证持久化）。
     */
    public ImportResult importSingle(String rawMarkdown, String operator) {
        return importPackage(null, List.of(new PackageFile("SKILL.md", "SKILL.md",
                rawMarkdown.getBytes(StandardCharsets.UTF_8))), operator);
    }

    /**
     * 导入一个 skill 目录：必须包含 SKILL.md，frontmatter 合法才落盘。
     */
    @Transactional
    public ImportResult importPackage(String rootDirName, List<PackageFile> files, String operator) {
        if (files == null || files.isEmpty()) {
            throw new CustomException("未收到任何文件", HttpStatus.BAD_REQUEST);
        }
        if (files.size() > MAX_FILE_COUNT) {
            throw new CustomException("单个 Skill 目录最多 " + MAX_FILE_COUNT + " 个文件，当前 " + files.size(),
                    HttpStatus.BAD_REQUEST);
        }

        List<PreparedFile> prepared = new ArrayList<>();
        long totalBytes = 0L;
        for (PackageFile file : files) {
            String relPath = normalizeRelPath(file.relPath(), file.originalName(), rootDirName);
            byte[] content = file.content() == null ? new byte[0] : file.content();
            if (content.length > MAX_FILE_BYTES) {
                throw new CustomException("文件 " + relPath + " 超过单文件上限 512KB", HttpStatus.BAD_REQUEST);
            }
            totalBytes += content.length;
            prepared.add(new PreparedFile(relPath, content, isBinary(content)));
        }
        if (totalBytes > MAX_PACKAGE_BYTES) {
            throw new CustomException("Skill 目录总大小超过 5MB 上限", HttpStatus.BAD_REQUEST);
        }

        PreparedFile entry = prepared.stream()
                .filter(item -> item.relPath().equalsIgnoreCase("SKILL.md")
                        || item.relPath().toLowerCase(Locale.ROOT).endsWith("/skill.md"))
                .min(Comparator.comparingInt(item -> item.relPath().length()))
                .orElseThrow(() -> new CustomException(
                        "目录中未找到入口文件 SKILL.md（应位于目录根或最浅一层）", HttpStatus.BAD_REQUEST));
        // 入口文件统一归一为 <dir>/SKILL.md，其余同路径冲突的文件视为重复
        String entryRelPath = "SKILL.md";
        List<PreparedFile> finalPrepared = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PreparedFile item : prepared) {
            String normalized = item == entry ? entryRelPath : item.relPath();
            if (!seen.add(normalized.toLowerCase(Locale.ROOT))) {
                throw new CustomException("存在同名文件（大小写不敏感）：" + normalized, HttpStatus.BAD_REQUEST);
            }
            finalPrepared.add(new PreparedFile(normalized, item.content(), item.binary()));
        }

        String rawMarkdown = new String(entry.content(), StandardCharsets.UTF_8);
        SkillFrontmatterParser.ParseResult parsed = frontmatterParser.parse(rawMarkdown);
        if (parsed == null) {
            throw new CustomException("SKILL.md 解析失败："
                    + frontmatterParser.failureReason(rawMarkdown), HttpStatus.BAD_REQUEST);
        }
        Skill skill = parsed.skill();
        String name = skill.getName();
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new CustomException("Skill name 必须是 kebab-case：" + name, HttpStatus.BAD_REQUEST);
        }

        Path skillDir = skillDirPath(name);
        Set<String> relPaths = new LinkedHashSet<>();
        for (PreparedFile item : finalPrepared) {
            Path target = resolveWithinDir(skillDir, item.relPath());
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, item.content());
            } catch (IOException e) {
                throw new CustomException("写入 Skill 文件失败：" + item.relPath() + " → " + e.getMessage(),
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            relPaths.add(item.relPath());
        }
        pruneStaleFiles(skillDir, relPaths);

        List<String> missingFiles = findMissingReferences(parsed.bodyWithoutFrontmatter(), relPaths);
        List<String> missingTools = findUnknownToolReferences(parsed.bodyWithoutFrontmatter());
        boolean needsExecution = detectExecutionNeed(parsed.bodyWithoutFrontmatter(), relPaths);

        List<String> notes = new ArrayList<>();
        for (String missing : missingFiles) {
            notes.add("引用文件缺失：" + missing);
        }
        for (String missing : missingTools) {
            notes.add("疑似引用未注册工具：" + missing);
        }
        if (needsExecution) {
            notes.add("含脚本执行诉求：当前无执行通道，附属脚本仅可读（要真跑需接入 exec 类 MCP 并走审批）");
        }
        boolean degraded = !notes.isEmpty();
        // 不影响完整性的提醒（比如二进制素材只登记路径不存内容），单独作为 warnings 回给操作员
        List<String> warnings = new ArrayList<>(notes);
        for (PreparedFile item : finalPrepared) {
            if (item.binary()) {
                warnings.add("二进制素材未入库内容，仅登记路径：" + item.relPath());
            }
        }

        SkillPackage entity = packageRepository.findByName(name).orElseGet(SkillPackage::new);
        entity.setName(name);
        entity.setDescription(skill.getDescription());
        entity.setVersion(nullToEmpty(skill.getVersion()));
        entity.setAuthor(nullToEmpty(skill.getAuthor()));
        entity.setTags(nullToEmpty(skill.getTags()));
        entity.setDirPath(relativeOf(skillDir));
        entity.setEntryPath(relativeOf(skillDir.resolve(entryRelPath)));
        entity.setBody(parsed.bodyWithoutFrontmatter());
        entity.setExtraMetadata(toJson(extraFields(parsed)));
        entity.setFileCount(finalPrepared.size());
        entity.setTotalBytes(totalBytes);
        entity.setStatus(degraded ? SkillPackage.STATUS_DEGRADED : SkillPackage.STATUS_READY);
        entity.setMissingDeps(toJson(notes));
        if (entity.getCreatedBy() == null) {
            entity.setCreatedBy(operator);
        }
        SkillPackage saved = packageRepository.save(entity);

        fileRepository.deleteBySkillName(name);
        List<SkillFileEntry> rows = new ArrayList<>();
        for (PreparedFile item : finalPrepared) {
            SkillFileEntry row = new SkillFileEntry();
            row.setSkillName(name);
            row.setRelPath(item.relPath());
            row.setMediaType(mediaTypeOf(item.relPath()));
            row.setSizeBytes((long) item.content().length);
            row.setSha256(DigestUtils.sha256Hex(item.content()));
            row.setTextReadable(!item.binary() && item.content().length <= MAX_FILE_BYTES);
            row.setIsEntry(item.relPath().equals(entryRelPath));
            rows.add(row);
        }
        fileRepository.saveAll(rows);

        // 刷新提示词缓存：索引段/启停状态仍走 Redis，但内容真相在磁盘 + MySQL
        skillStateStore.save(toCachedSkill(saved));

        logger.info("Skill 包导入完成: name={}, files={}, bytes={}, status={}, operator={}",
                name, finalPrepared.size(), totalBytes, saved.getStatus(), operator);
        return new ImportResult(saved, warnings);
    }

    // ====== 读取 ======

    public Optional<SkillPackage> find(String name) {
        return packageRepository.findByName(name);
    }

    public List<SkillFileEntry> listFiles(String name) {
        return fileRepository.findBySkillNameOrderByRelPathAsc(name);
    }

    /** 依赖校验结论：把 missing_deps 的 JSON 数组字符串还原成列表，供接口与前端渲染 */
    public List<String> missingDepsList(SkillPackage pkg) {
        String raw = pkg == null ? null : pkg.getMissingDeps();
        if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of(raw);
        }
    }

    /**
     * 读取入口 SKILL.md 正文，按行分页。磁盘优先，缺失时回落到 MySQL body 兜底。
     */
    public EntryText readEntry(String name, int offset, int limit) {
        SkillPackage pkg = packageRepository.findByName(name)
                .orElseThrow(() -> new CustomException("Skill 不存在: " + name, HttpStatus.NOT_FOUND));
        int from = Math.max(1, offset);
        int count = Math.min(limit <= 0 ? DEFAULT_ENTRY_LIMIT_LINES : limit, MAX_ENTRY_LIMIT_LINES);

        List<String> lines = readEntryLines(pkg);
        if (lines.isEmpty()) {
            return new EntryText(pkg.getEntryPath(), "", 0, 0, 0, false);
        }
        int start = Math.min(from - 1, lines.size());
        int end = Math.min(start + count, lines.size());
        String content = String.join("\n", lines.subList(start, end));
        return new EntryText(pkg.getEntryPath(), content, lines.size(), start + 1, end, end < lines.size());
    }

    /**
     * 读取入口文件全文（含 frontmatter），供前端预览与下载。
     * 磁盘优先；文件丢失时用 MySQL 元数据 + body 重建一份等价 markdown。
     */
    public String readEntryRaw(String name) {
        SkillPackage pkg = packageRepository.findByName(name)
                .orElseThrow(() -> new CustomException("Skill 不存在: " + name, HttpStatus.NOT_FOUND));
        Path entry = Paths.get(pkg.getEntryPath());
        if (Files.exists(entry) && !isBinary(readBytes(entry))) {
            return new String(readBytes(entry), StandardCharsets.UTF_8);
        }
        return toRawMarkdown(toCachedSkill(pkg));
    }

    /**
     * 读取包内任意文本文件（前端预览用；Agent 侧走内置 read_file，不经这里）。
     */
    public String readTextFile(String name, String relPath, int offset, int limit) {
        SkillPackage pkg = packageRepository.findByName(name)
                .orElseThrow(() -> new CustomException("Skill 不存在: " + name, HttpStatus.NOT_FOUND));
        Path target = resolveWithinDir(Paths.get(pkg.getDirPath()).toAbsolutePath().normalize(),
                normalizeRelPath(relPath, relPath, null));
        if (!Files.exists(target)) {
            throw new CustomException("文件不存在: " + relPath, HttpStatus.NOT_FOUND);
        }
        if (isBinary(readBytes(target))) {
            throw new CustomException("该文件是二进制素材，仅登记路径，无法预览：" + relPath, HttpStatus.BAD_REQUEST);
        }
        List<String> lines = readLines(target);
        int from = Math.max(1, offset);
        int size = limit <= 0 ? DEFAULT_ENTRY_LIMIT_LINES : Math.min(limit, MAX_ENTRY_LIMIT_LINES);
        int start = Math.min(from - 1, lines.size());
        int end = Math.min(start + size, lines.size());
        return String.join("\n", lines.subList(start, end));
    }

    /**
     * 给 load_skill 的正文缓冲用：全文过长时截断，但一定带上"如何续读"的路径与 offset，
     * 让截断可恢复——这是对原先 5000 字符静默截断的直接修复。
     */
    public String buildBufferBody(SkillPackage pkg, EntryText entry) {
        String text = entry.content();
        if (text.length() <= BUFFER_BODY_CHARS) {
            if (entry.truncated()) {
                return text + "\n\n（SKILL.md 还有 " + (entry.totalLines() - entry.to())
                        + " 行未展示，请用 read_file 读取 " + entry.path() + "，offset=" + (entry.to() + 1) + "）";
            }
            return text;
        }
        String cut = text.substring(0, BUFFER_BODY_CHARS);
        return cut + "\n...[已截断，可用 read_file 读取 " + entry.path() + "，offset=" + (entry.to() + 1)
                + " 继续；单个附属文件按需只读需要的那一个，不要把整个目录读进上下文]"
                + "\n（提示：包内共 " + (pkg.getFileCount() == null ? 0 : pkg.getFileCount()) + " 个文件）";
    }

    // ====== 删除 ======

    @Transactional
    public boolean deletePackage(String name) {
        Optional<SkillPackage> pkg = packageRepository.findByName(name);
        if (pkg.isEmpty()) {
            // 兼容未迁移的旧数据：Redis 里有但没有包记录时，按旧口径删除缓存
            if (skillStateStore.exists(name)) {
                skillStateStore.delete(name);
                return true;
            }
            return false;
        }
        Path dir = Paths.get(pkg.get().getDirPath()).toAbsolutePath().normalize();
        deleteRecursively(dir);
        fileRepository.deleteBySkillName(name);
        packageRepository.deleteByName(name);
        skillStateStore.delete(name);
        logger.info("Skill 包已删除: name={}, dir={}", name, dir);
        return true;
    }

    // ====== 旧数据迁移（Redis-only → 磁盘 + MySQL） ======

    /**
     * 把只存在于 Redis 的历史 Skill 落成目录包。返回迁移条数；MySQL 已有同名包则跳过。
     */
    public int migrateLegacyFromRedis() {
        List<Skill> cached = skillStateStore.listAll();
        int migrated = 0;
        for (Skill skill : cached) {
            if (skill.getName() == null || packageRepository.existsByName(skill.getName())) {
                continue;
            }
            try {
                importSingle(toRawMarkdown(skill), skillStateStoreOperator());
                migrated++;
            } catch (RuntimeException e) {
                logger.warn("旧 Skill 迁移失败: name={}, error={}", skill.getName(), e.getMessage());
            }
        }
        if (migrated > 0) {
            logger.info("Skill 旧数据迁移完成：{} 个 Redis-only Skill 已落盘并入库", migrated);
        }
        return migrated;
    }

    private String skillStateStoreOperator() {
        return "system-migrate";
    }

    /**
     * 用 MySQL 清单重建 Redis 提示词缓存。
     * Redis 被清空或换了实例时，技能索引不应丢失——磁盘目录与 skill_packages 才是真相。
     */
    public int syncCacheFromDatabase() {
        List<SkillPackage> packages = packageRepository.findAll();
        for (SkillPackage pkg : packages) {
            skillStateStore.save(toCachedSkill(pkg));
        }
        return packages.size();
    }

    // ====== Markdown 重建（供下载/迁移复用） ======

    public String toRawMarkdown(Skill skill) {
        StringBuilder markdown = new StringBuilder("---\n");
        markdown.append("name: ").append(skill.getName()).append('\n');
        markdown.append("description: ").append(skill.getDescription()).append('\n');
        if (isNotBlank(skill.getVersion())) {
            markdown.append("version: ").append(skill.getVersion()).append('\n');
        }
        if (isNotBlank(skill.getAuthor())) {
            markdown.append("author: ").append(skill.getAuthor()).append('\n');
        }
        if (isNotBlank(skill.getTags())) {
            markdown.append("tags: [").append(skill.getTags()).append("]\n");
        }
        markdown.append("---\n\n").append(skill.getBody() == null ? "" : skill.getBody());
        return markdown.toString();
    }

    /** Redis 缓存形态：额外带上目录路径与完整性状态，索引段直接渲染这些字段 */
    public Skill toCachedSkill(SkillPackage pkg) {
        return new Skill(pkg.getName(), pkg.getDescription(), pkg.getVersion(), pkg.getAuthor(), pkg.getTags(),
                pkg.getBody(), pkg.getEntryPath(), pkg.getStatus(), pkg.getMissingDeps());
    }

    // ====== 依赖校验 ======

    private List<String> findMissingReferences(String body, Set<String> relPaths) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        Set<String> present = new HashSet<>();
        for (String rel : relPaths) {
            present.add(rel.toLowerCase(Locale.ROOT));
            present.add("./" + rel.toLowerCase(Locale.ROOT));
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = REL_PATH_REF.matcher(body);
        while (matcher.find()) {
            String ref = matcher.group();
            if (ref.contains("://")) {
                continue;
            }
            String key = ref.toLowerCase(Locale.ROOT);
            if (!present.contains(key) && !present.contains(key.replaceFirst("^\\./", ""))) {
                missing.add(ref);
            }
        }
        return new ArrayList<>(missing);
    }

    private List<String> findUnknownToolReferences(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        Set<String> known = registeredToolNames();
        if (known.isEmpty()) {
            // 拿不到工具清单时不做判定，避免把整套 Skill 误标为降级
            return List.of();
        }
        Set<String> suspects = new LinkedHashSet<>();
        for (String line : body.split("\n")) {
            if (!containsCallContext(line)) {
                continue;
            }
            Matcher matcher = TOOL_TOKEN.matcher(line);
            while (matcher.find()) {
                String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                if (token == null || token.isBlank()) {
                    continue;
                }
                boolean toolShaped = token.startsWith("mcp__") || token.contains("_");
                if (toolShaped && !known.contains(token)) {
                    suspects.add(token);
                }
            }
        }
        return new ArrayList<>(suspects);
    }

    private boolean containsCallContext(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String keyword : CALL_CONTEXT_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean detectExecutionNeed(String body, Set<String> relPaths) {
        for (String rel : relPaths) {
            int dot = rel.lastIndexOf('.');
            if (dot > 0 && SCRIPT_EXTENSIONS.contains(rel.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return body != null && EXEC_HINT.matcher(body).find();
    }

    private Set<String> registeredToolNames() {
        try {
            AgentToolRegistry registry = toolRegistryProvider.getIfAvailable();
            if (registry == null) {
                return Set.of();
            }
            return registry.getTools().stream()
                    .map(AgentToolRegistry.AgentTool::name)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            logger.warn("读取已注册工具清单失败，跳过工具依赖校验: {}", e.getMessage());
            return Set.of();
        }
    }

    private Map<String, String> extraFields(SkillFrontmatterParser.ParseResult parsed) {
        Map<String, String> extra = new LinkedHashMap<>(parsed.fields());
        extra.keySet().removeAll(Set.of("name", "description", "version", "author", "tags"));
        return extra;
    }

    // ====== 路径与文件工具 ======

    /** 围栏根下的 skill 存储根目录：data/skills */
    public Path baseDir() {
        return Paths.get(skillStoragePath == null || skillStoragePath.isBlank() ? "data/skills" : skillStoragePath)
                .toAbsolutePath().normalize();
    }

    public Path skillDirPath(String name) {
        return baseDir().resolve(name).normalize();
    }

    /**
     * 目录内解析：既防越出 skill 目录（.. / 绝对路径），也防符号链接逃逸。
     * 每个 skill 独占一个子目录，因此 skill 之间也是隔离的。
     */
    private Path resolveWithinDir(Path skillDir, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new CustomException("文件路径不能为空", HttpStatus.BAD_REQUEST);
        }
        if (relPath.contains("..")) {
            throw new CustomException("文件路径不允许包含 ..：" + relPath, HttpStatus.BAD_REQUEST);
        }
        Path target = skillDir.resolve(relPath).normalize();
        if (!target.startsWith(skillDir)) {
            throw new CustomException("文件路径越出 Skill 目录：" + relPath, HttpStatus.BAD_REQUEST);
        }
        Path existing = target;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            try {
                Path real = existing.toRealPath();
                if (!real.startsWith(skillDir.toRealPath())) {
                    throw new CustomException("文件路径指向 Skill 目录外部（疑似符号链接逃逸）：" + relPath,
                            HttpStatus.BAD_REQUEST);
                }
            } catch (IOException ignored) {
                // 目录尚未落盘时 toRealPath 会失败，已由上面的 startsWith(normalize) 兜住
            }
        }
        return target;
    }

    /** 归一化相对路径：反斜杠转正斜杠、剥离 ./ 与所选根目录名、剔除绝对路径与非法字符 */
    private String normalizeRelPath(String rawRel, String originalName, String rootDirName) {
        String candidate = rawRel != null && !rawRel.isBlank()
                ? rawRel
                : (originalName == null ? "" : originalName);
        String path = candidate.replace('\\', '/').trim();
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.startsWith("/") || (path.length() >= 2 && path.charAt(1) == ':')) {
            throw new CustomException("不接受绝对路径：" + candidate, HttpStatus.BAD_REQUEST);
        }
        if (rootDirName != null && !rootDirName.isBlank()) {
            String prefix = rootDirName.replace('\\', '/').trim();
            while (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            if (path.equalsIgnoreCase(prefix)) {
                path = "";
            } else if (path.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT) + "/")) {
                path = path.substring(prefix.length() + 1);
            }
        }
        path = path.replaceAll("[\\:*?\"<>|]", "_");
        if (path.isBlank()) {
            throw new CustomException("文件路径为空", HttpStatus.BAD_REQUEST);
        }
        return path;
    }

    private String relativeOf(Path absolute) {
        try {
            return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
                    .relativize(absolute).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return absolute.toString().replace('\\', '/');
        }
    }

    private List<String> readEntryLines(SkillPackage pkg) {
        Path entry = Paths.get(pkg.getEntryPath());
        if (Files.exists(entry)) {
            // 磁盘文件含 frontmatter，而 frontmatter 已经由索引段给出，这里剔掉，
            // 避免模型把 name/description/allowed-tools 当成正文重复读一遍
            return stripFrontmatter(readLines(entry));
        }
        String fallback = pkg.getBody() == null ? "" : pkg.getBody();
        return fallback.isEmpty() ? List.of() : List.of(fallback.split("\n"));
    }

    private static List<String> stripFrontmatter(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
            return lines;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("---")) {
                return new ArrayList<>(lines.subList(i + 1, lines.size()));
            }
        }
        // 只有起始 --- 没有闭合：当作无 frontmatter，不丢内容
        return lines;
    }

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CustomException("读取 Skill 文件失败：" + file + " → " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new CustomException("读取 Skill 文件失败：" + file + " → " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void pruneStaleFiles(Path skillDir, Set<String> keepRelPaths) {
        Set<String> keep = new HashSet<>();
        for (String rel : keepRelPaths) {
            keep.add(skillDir.resolve(rel).normalize().toString());
        }
        if (!Files.isDirectory(skillDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(skillDir)) {
            List<Path> stale = new ArrayList<>();
            walk.filter(Files::isRegularFile).filter(p -> !keep.contains(p.normalize().toString()))
                    .forEach(stale::add);
            for (Path path : stale) {
                Files.deleteIfExists(path);
                logger.info("清理 Skill 目录中的过期文件: {}", path);
            }
        } catch (IOException e) {
            logger.warn("清理 Skill 过期文件失败: dir={}, error={}", skillDir, e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            logger.warn("删除 Skill 目录失败: dir={}, error={}", dir, e.getMessage());
        }
    }

    private static boolean isBinary(byte[] content) {
        int head = Math.min(content.length, 1024);
        for (int i = 0; i < head; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String mediaTypeOf(String relPath) {
        int dot = relPath.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        return MEDIA_TYPES.getOrDefault(relPath.substring(dot + 1).toLowerCase(Locale.ROOT),
                "application/octet-stream");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record PreparedFile(String relPath, byte[] content, boolean binary) {}
}

