package com.collectivelaunchpad.domain;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A login: superadmin, seller (owns one shop) or customer. */
@Entity
@Table(name = "users")
public class Account {
    public static final String SUPERADMIN = "superadmin";
    public static final String SELLER = "seller";
    public static final String CUSTOMER = "customer";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String role;
    public String name;
    /** 10-digit Indian mobile number, digits only. */
    public String phone;
    /** Lowercase. */
    public String email;
    /** Null for people who only sign in with Google. */
    @Column(name = "password_hash")
    public String passwordHash;
    /** The Google account id, once they have signed in with Google. */
    @Column(name = "google_sub")
    public String googleSub;
    @Column(name = "seller_id")
    public Long sellerId;
    public boolean active = true;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
    @Column(name = "last_login_at")
    public OffsetDateTime lastLoginAt;

    public boolean isSuperadmin() {
        return SUPERADMIN.equals(role);
    }

    public boolean isSeller() {
        return SELLER.equals(role);
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    /** What to show as the login name. */
    public String login() {
        return phone != null ? phone : email;
    }
}
