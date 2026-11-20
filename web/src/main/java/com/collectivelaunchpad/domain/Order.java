package com.collectivelaunchpad.domain;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** One order from one customer to one shop. Paid to the seller directly (cash or UPI). */
@Entity
@Table(name = "orders")
public class Order {
    public static final String PLACED = "placed";
    public static final String ACCEPTED = "accepted";
    public static final String READY = "ready";
    public static final String COMPLETED = "completed";
    public static final String DECLINED = "declined";
    public static final String CANCELLED = "cancelled";

    public static final String COD = "cod";
    public static final String UPI = "upi";
    public static final String UNPAID = "unpaid";
    public static final String PAID = "paid";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "seller_id")
    public Long sellerId;
    @Column(name = "customer_id")
    public Long customerId;
    public String status = PLACED;
    @Column(name = "payment_method")
    public String paymentMethod;
    @Column(name = "payment_status")
    public String paymentStatus = UNPAID;
    @Column(name = "customer_marked_paid_at")
    public OffsetDateTime customerMarkedPaidAt;
    @Column(name = "paid_at")
    public OffsetDateTime paidAt;
    @Column(name = "total_paise")
    public long totalPaise;
    @Column(name = "customer_name")
    public String customerName;
    @Column(name = "customer_phone")
    public String customerPhone;
    public String delivery;
    public String note;
    @Column(name = "seller_note")
    public String sellerNote;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
    @Column(name = "updated_at")
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public boolean isPaid() {
        return PAID.equals(paymentStatus);
    }

    /** Still something for the seller or customer to do. */
    public boolean isActive() {
        return PLACED.equals(status) || ACCEPTED.equals(status) || READY.equals(status);
    }

    public boolean isClosedUnsuccessfully() {
        return DECLINED.equals(status) || CANCELLED.equals(status);
    }

    public String totalLabel() {
        return Money.label(totalPaise);
    }

    public String statusLabel() {
        return switch (status) {
            case PLACED -> "Waiting for the seller";
            case ACCEPTED -> "Accepted";
            case READY -> "Ready / on the way";
            case COMPLETED -> "Completed";
            case DECLINED -> "Declined by the seller";
            case CANCELLED -> "Cancelled";
            default -> status;
        };
    }

    public String paymentLabel() {
        return COD.equals(paymentMethod) ? "Cash on delivery / pickup" : "UPI to the seller";
    }
}
