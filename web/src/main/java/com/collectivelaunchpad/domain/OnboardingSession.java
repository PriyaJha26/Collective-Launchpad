package com.collectivelaunchpad.domain;

import java.time.OffsetDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** The onboarding agent's conversation for one seller. Stored here; the agent service is stateless. */
@Entity
@Table(name = "onboarding_sessions")
public class OnboardingSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "seller_id")
    public Long sellerId;
    /** Append-only message history; image blocks reference uploaded files. */
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode messages = JsonNodeFactory.instance.arrayNode();
    public String status;
    @Column(name = "pending_question")
    public String pendingQuestion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_tool_results")
    public JsonNode pendingToolResults = JsonNodeFactory.instance.arrayNode();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inflight_input")
    public JsonNode inflightInput;
    @Column(name = "last_error")
    public String lastError;
    @Column(name = "note_for_admin")
    public String noteForAdmin;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
    @Column(name = "updated_at")
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public boolean isWorking() {
        return Entities.SessionStatus.WORKING.equals(status);
    }
}
