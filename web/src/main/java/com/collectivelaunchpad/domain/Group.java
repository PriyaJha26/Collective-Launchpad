package com.collectivelaunchpad.domain;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The self-help group or cooperative. One row per deployment. */
@Entity
@Table(name = "groups")
public class Group {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String name;
    public String tagline;
    public String description;
    public String location;
    @Column(name = "contact_email")
    public String contactEmail;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
}
