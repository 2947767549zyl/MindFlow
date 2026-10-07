package com.mindflow.module.skill.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 包元数据：一个 Skill = 磁盘上的 data/skills/&lt;name&gt;/ 整个目录。
 *
 * 定位与 wiki_pages 一致——MySQL 是权威清单（重启/清 Redis 不丢），
 * 目录里的 SKILL.md 与附属文件留在磁盘上，供 Agent 用 read_file/glob 按需读取。
 * body 只存 SKILL.md 正文（去 frontmatter），作为磁盘文件缺失时的兜底；附属文件内容不入库。
 */
@Data
@Entity
@Table(name = "skill_packages")
public class SkillPackage {

    /** 附属文件缺失 / 工具未注册 / 需要脚本执行通道 */
    public static final String STATUS_READY = "READY";
    public static final String STATUS_DEGRADED = "DEGRADED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** frontmatter description 按码点限 1024（Agent Skills 口径），非 BMP 字符占 2 个 char，故留 2048 */
    @Column(length = 2048)
    private String description;

    @Column(length = 50)
    private String version;

    @Column(length = 200)
    private String author;

    @Column(length = 500)
    private String tags;

    /** 入口文件相对仓库根的路径，例如 data/skills/web-access/SKILL.md */
    @Column(name = "entry_path", length = 500)
    private String entryPath;

    /** 目录相对路径，例如 data/skills/web-access */
    @Column(name = "dir_path", length = 500)
    private String dirPath;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String body;

    /** frontmatter 里未被显式建模的其余键（allowed-tools / license 等），原样保留不丢 */
    @Lob
    @Column(name = "extra_metadata", columnDefinition = "TEXT")
    private String extraMetadata;

    @Column(name = "file_count")
    private Integer fileCount;

    @Column(name = "total_bytes")
    private Long totalBytes;

    @Column(length = 20)
    private String status;

    /** 依赖校验结论，JSON 数组字符串 */
    @Lob
    @Column(name = "missing_deps", columnDefinition = "TEXT")
    private String missingDeps;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = STATUS_READY;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
