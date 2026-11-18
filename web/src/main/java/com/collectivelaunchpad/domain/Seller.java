package com.collectivelaunchpad.domain;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "sellers")
public class Seller {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "group_id")
    public Long groupId;
    public String slug;
    public String name;
    @Column(name = "shop_name")
    public String shopName;
    public String bio;
    public String location;
    public String language;
    public String whatsapp;
    public String status = Entities.SellerStatus.DRAFT;
    @Column(name = "review_fields")
    public String reviewFields = "";
    @Column(name = "verified_at")
    public OffsetDateTime verifiedAt;
    @Column(name = "live_check_ok")
    public Boolean liveCheckOk;
    @Column(name = "live_check_at")
    public OffsetDateTime liveCheckAt;
    @Column(name = "live_check_detail")
    public String liveCheckDetail;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
    @Column(name = "updated_at")
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public boolean isPublished() {
        return Entities.SellerStatus.PUBLISHED.equals(status);
    }

    /** The agent may only change drafts; published and hidden shops are the admin's. */
    public boolean isEditableByAgent() {
        return Entities.SellerStatus.DRAFT.equals(status) || Entities.SellerStatus.PENDING_REVIEW.equals(status);
    }

    public boolean unsure(String field) {
        return reviewFieldSet().contains(field);
    }

    public Set<String> reviewFieldSet() {
        return Arrays.stream(reviewFields == null ? new String[0] : reviewFields.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** Digits for a wa.me link; 10-digit Indian numbers get the 91 country code. */
    public String whatsappDigits() {
        if (whatsapp == null) {
            return null;
        }
        String digits = whatsapp.replaceAll("\\D", "");
        if (digits.length() == 10) {
            digits = "91" + digits;
        }
        return digits.length() >= 11 ? digits : null;
    }
}
