package com.collectivelaunchpad.domain;

import java.time.OffsetDateTime;

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
    @Column(name = "ai_error")
    public String aiError;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;

    public boolean isOpen() {
        return Entities.EnquiryStatus.NEW.equals(status) || Entities.EnquiryStatus.DRAFTED.equals(status);
    }

    public boolean hasEmail() {
        return customerEmail != null && !customerEmail.isBlank();
    }
}
