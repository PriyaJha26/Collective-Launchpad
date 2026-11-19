package com.collectivelaunchpad.domain;

import java.time.OffsetDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.collectivelaunchpad.i18n.Languages;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A message from a customer, sent through a shop's contact form. */
@Entity
@Table(name = "enquiries")
public class Enquiry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "seller_id")
    public Long sellerId;
    @Column(name = "customer_name")
    public String customerName;
    @Column(name = "customer_email")
    public String customerEmail;
    @Column(name = "customer_phone")
    public String customerPhone;
    public String message;
    public String category;
    public String urgency;
    public String language;
    public String status = Entities.EnquiryStatus.NEW;
    /** What the customer chose: question, discount, custom, order. */
    public String topic;
    /** Set when the customer was logged in, so replies show in their account. */
    @Column(name = "customer_user_id")
    public Long customerUserId;
    @Column(name = "ai_error")
    public String aiError;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
    /** The website language the customer was using when they wrote; replies are translated into it. */
    @Column(name = "customer_language")
    public String customerLanguage;
    /** The message in other languages: {"mr": "...", "en": "..."}. */
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode translations;

    /** The message in the reader's language, or as written when there is no translation yet. */
    public String messageIn(String lang) {
        return lang != null && lang.equals(customerLanguage) ? message : Languages.pick(translations, lang, message);
    }

    /** True when the message is shown translated (so the original is worth offering). */
    public boolean isTranslatedFor(String lang) {
        return !messageIn(lang).equals(message);
    }

    public boolean needsTranslationFor(String lang) {
        return lang != null && !lang.equals(customerLanguage) && !Languages.has(translations, lang);
    }

    public boolean isOpen() {
        return Entities.EnquiryStatus.NEW.equals(status) || Entities.EnquiryStatus.DRAFTED.equals(status);
    }

    public String topicLabel() {
        if (topic == null) {
            return null;
        }
        return switch (topic) {
            case "discount" -> "Asks for a discount";
            case "custom" -> "Custom order request";
            case "order" -> "About an order";
            default -> "Question";
        };
    }

    public boolean hasEmail() {
        return customerEmail != null && !customerEmail.isBlank();
    }
}
