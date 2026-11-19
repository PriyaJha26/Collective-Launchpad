package com.collectivelaunchpad.domain;

import java.time.OffsetDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.collectivelaunchpad.i18n.Languages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "replies")
public class Reply {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "enquiry_id")
    public Long enquiryId;
    public String body;
    /** Points the admin should confirm with the seller before sending. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "check_points")
    public JsonNode checkPoints = JsonNodeFactory.instance.arrayNode();
    @Column(name = "drafted_by")
    public String draftedBy;
    public String status;
    @Column(name = "approved_by")
    public Long approvedBy;
    @Column(name = "sent_at")
    public OffsetDateTime sentAt;
    @Column(name = "send_error")
    public String sendError;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
    /** The language the reply was written in (the seller's or the admin's). */
    public String language;
    /** The reply in other languages, at least the customer's: {"ta": "..."}. */
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode translations;

    public String bodyIn(String lang) {
        return lang != null && lang.equals(language) ? body : Languages.pick(translations, lang, body);
    }

    public boolean isTranslatedFor(String lang) {
        return !bodyIn(lang).equals(body);
    }
}
