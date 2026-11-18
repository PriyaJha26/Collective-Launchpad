package com.collectivelaunchpad.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "seller_id")
    public Long sellerId;
    public String name;
    public String description = "";
    @Column(name = "price_paise")
    public Long pricePaise;
    public String unit = "";
    @Column(name = "image_path")
    public String imagePath;
    @Column(name = "is_available")
    public boolean isAvailable = true;
    @Column(name = "needs_review")
    public boolean needsReview;
    @Column(name = "review_fields")
    public String reviewFields = "";
    @Column(name = "sort_order")
    public int sortOrder;

    public String priceLabel() {
        if (pricePaise == null) {
            return "Ask for price";
        }
        long rupees = pricePaise / 100;
        long paise = pricePaise % 100;
        String whole = String.format("%,d", rupees);
        return "₹" + (paise == 0 ? whole : whole + "." + String.format("%02d", paise));
    }

    /** Price in rupees for form fields, e.g. "450" or "12.50"; empty when unknown. */
    public String priceRupeesText() {
        if (pricePaise == null) {
            return "";
        }
        return pricePaise % 100 == 0 ? Long.toString(pricePaise / 100) : String.format("%d.%02d", pricePaise / 100, pricePaise % 100);
    }

    public String imageUrl() {
        return imagePath == null ? null : "/uploads/" + imagePath;
    }

    public boolean unsure(String field) {
        return reviewFieldSet().contains(field);
    }

    public Set<String> reviewFieldSet() {
        return Arrays.stream(reviewFields == null ? new String[0] : reviewFields.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
}
