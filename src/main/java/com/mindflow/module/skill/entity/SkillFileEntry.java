package com.mindflow.module.skill.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 包内单个文件的清单（内容留在磁盘，不入库）。
 *
 * 用途有两个：导入时做依赖校验（SKILL.md 引用的相对路径是否真的存在），
 * 以及 load_skill 时把文件清单回灌给模型，让它知道能继续 read_file 哪些文件。
 */
@Data
@Entity
@Table(name = "skill_files")
public class SkillFileEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_name", nullable = false, length = 100)
    private String skillName;

    /** 相对 skill 目录的路径，统一正斜杠，例如 references/api.md */
    @Column(name = "rel_path", nullable = false, length = 400)
    private String relPath;

    @Column(name = "media_type", length = 100)
    private String mediaType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 64)
    private String sha256;

    /** 文本可读：非二进制且体积在阈值内，Agent 可用 read_file 打开 */
    @Column(name = "text_readable")
    private Boolean textReadable;

    @Column(name = "is_entry")
    private Boolean isEntry;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
