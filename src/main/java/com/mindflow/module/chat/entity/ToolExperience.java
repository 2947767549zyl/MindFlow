package com.mindflow.module.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 工具失败经验（episodic memory）：沉淀"哪个工具 + 哪类失败 + 出现过几次 + 恢复策略"。
 * 目标不是记住用户说了什么，而是让 Agent 记住自己是怎么失败的，避免同类错误重复踩。
 */
@Data
@Entity
@Table(name = "tool_experience",
        uniqueConstraints = @UniqueConstraint(name = "uk_te_signature", columnNames = "signature"),
        indexes = @Index(name = "idx_te_tool", columnList = "tool_name"))
public class ToolExperience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tool_name", length = 120, nullable = false)
    private String toolName;

    @Column(name = "failure_category", length = 40, nullable = false)
    private String failureCategory;

    @Column(length = 200, nullable = false)
    private String signature;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(name = "last_error_text", length = 500)
    private String lastErrorText;

    @Column(name = "recovery_hint", length = 300)
    private String recoveryHint;

    @CreationTimestamp
    @Column(name = "first_seen_at", updatable = false)
    private LocalDateTime firstSeenAt;

    @UpdateTimestamp
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;
}
