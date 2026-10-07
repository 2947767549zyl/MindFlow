package com.mindflow.harness.skill;

/**
 * Skill 单元：SKILL.md 解析后的内存形态（frontmatter 元数据 + 正文决策手册）。
 *
 * entryPath/status/missingDeps 是"目录化 Skill"带来的扩展字段：
 * 由 SkillPackageService 从 skill_packages 表填充，Redis 只作为提示词缓存。
 * 旧的 6 参构造保留（这三个字段留空），用于兼容仅有 SKILL.md 文本的历史数据。
 */
public class Skill {

    private final String name;
    private final String description;
    private final String version;
    private final String author;
    private final String tags;
    private final String body;
    private final String entryPath;
    private final String status;
    private final String missingDeps;

    public Skill(String name, String description, String version, String author, String tags, String body) {
        this(name, description, version, author, tags, body, null, null, null);
    }

    public Skill(String name, String description, String version, String author, String tags, String body,
                 String entryPath, String status, String missingDeps) {
        this.name = name;
        this.description = description;
        this.version = version;
        this.author = author;
        this.tags = tags;
        this.body = body;
        this.entryPath = entryPath;
        this.status = status;
        this.missingDeps = missingDeps;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public String getAuthor() { return author; }
    public String getTags() { return tags; }
    public String getBody() { return body; }
    public String getEntryPath() { return entryPath; }
    public String getStatus() { return status; }
    public String getMissingDeps() { return missingDeps; }

    /** 是否已落成目录包（有入口文件路径才算三级披露可用） */
    public boolean hasPackage() {
        return entryPath != null && !entryPath.isBlank();
    }

    public boolean degraded() {
        return "DEGRADED".equalsIgnoreCase(status);
    }
}
