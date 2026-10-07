package com.mindflow.module.mcp.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "mcp_server_configs", indexes = {
        @Index(name = "idx_mcp_server_name", columnList = "server_name", unique = true)
})
public class McpServerConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_name", nullable = false, unique = true, length = 120)
    private String name;

    @Column(name = "transport_type", nullable = false, length = 16)
    private String transportType;

    @Column(length = 500)
    private String command;

    @Column(name = "args_json", columnDefinition = "TEXT")
    private String argsJson;

    @Column(length = 1000)
    private String url;

    @Column(name = "headers_json", columnDefinition = "TEXT")
    private String headersJson;

    @Column(name = "env_json", columnDefinition = "TEXT")
    private String envJson;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 64)
    private String createdBy;

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
