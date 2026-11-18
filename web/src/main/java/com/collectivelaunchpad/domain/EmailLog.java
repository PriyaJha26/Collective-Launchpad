package com.collectivelaunchpad.domain;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "email_log")
public class EmailLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "from_address")
    public String fromAddress;
    @Column(name = "to_address")
    public String toAddress;
    @Column(name = "reply_to")
    public String replyTo;
    public String subject;
    public String body;
    /** SENT, SIMULATED or "FAILED: reason". */
    public String result;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
}
