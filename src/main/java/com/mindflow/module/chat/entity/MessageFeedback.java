package com.mindflow.module.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "message_feedback", indexes = {
        @Index(name = "idx_mf_user_created", columnList = "user_id,created_at"),
        @Index(name = "idx_mf_conversation", columnList = "conversation_id")
})
public class MessageFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "generation_id", length = 64)
    private String generationId;

    @Column(length = 16, nullable = false)
    private String rating;

    @Column(length = 500)
    private String reason;

    @Column(name = "answer_excerpt", length = 1000)
    private String answerExcerpt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
