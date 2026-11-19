package com.collectivelaunchpad.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A line of an order. Name, unit and price are copied when the order is placed. */
@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "order_id")
    public Long orderId;
    @Column(name = "product_id")
    public Long productId;
    public String name;
    public String unit = "";
    @Column(name = "price_paise")
    public long pricePaise;
    public int quantity;

    public long lineTotalPaise() {
        return pricePaise * quantity;
    }

    public String priceLabel() {
        return Money.label(pricePaise);
    }

    public String lineTotalLabel() {
        return Money.label(lineTotalPaise());
    }
}
