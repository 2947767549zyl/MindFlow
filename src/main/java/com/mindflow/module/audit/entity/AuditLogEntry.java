package com.mindflow.module.audit.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_tool", columnList = "tool"),
        @Index(name = "idx_audit_user", columnList = "user_id")
})
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String timestamp;

    @Column(nullable = false, length = 120)
    private String tool;

    @Column(columnDefinition = "TEXT")
    private String args;

    @Column(nullable = false, length = 20)
    private String outcome;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false, length = 20)
    private String approver;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
