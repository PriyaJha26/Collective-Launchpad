package com.collectivelaunchpad.domain;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A message to the group admin from the main site: a request to join, or a question. */
@Entity
@Table(name = "group_requests")
public class GroupRequest {
    public static final String JOIN = "join";
    public static final String QUESTION = "question";

    public static final String NEW = "new";
    public static final String IN_PROGRESS = "in_progress";
    public static final String DONE = "done";
    public static final String DECLINED = "declined";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String kind;
    public String name;
    public String email;
    public String phone;
    public String location;
    public String language;
    public String craft;
    public String message;
    public String status = NEW;
    @Column(name = "seller_id")
    public Long sellerId;
    /** English translation of the free-text fields, when the request was written in another language. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "translation_en")
    public com.fasterxml.jackson.databind.JsonNode translationEn;
    @Column(name = "source_language")
    public String sourceLanguage;

    public String english(String field) {
        if (translationEn == null || !translationEn.hasNonNull(field)) {
            return null;
        }
        String t = translationEn.get(field).asText();
        return t.isBlank() ? null : t;
    }

    @Column(name = "reply_sent")
    public String replySent;
    @Column(name = "replied_at")
    public OffsetDateTime repliedAt;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
    @Column(name = "updated_at")
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public boolean isJoin() {
        return JOIN.equals(kind);
    }

    public boolean isOpen() {
        return NEW.equals(status) || IN_PROGRESS.equals(status);
    }

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}
