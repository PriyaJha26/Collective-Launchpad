package com.collectivelaunchpad.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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
    /** Units in stock; null means made to order (not counted). */
    public Integer stock;
    /** Product texts in other languages: {"hi": {"name": .., "description": .., "unit": ..}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    public JsonNode i18n;
    @Column(name = "i18n_source_hash")
    public String i18nSourceHash;

    public String nameIn(String lang) {
        return Languages.pick(i18n, lang, "name", name);
    }

    public String descriptionIn(String lang) {
        return Languages.pick(i18n, lang, "description", description);
    }

    public String unitIn(String lang) {
        return Languages.pick(i18n, lang, "unit", unit);
    }

    public boolean canBeOrdered() {
        return isAvailable && pricePaise != null && (stock == null || stock > 0);
    }

    public String stockLabel() {
        if (stock == null) {
            return "Made to order";
        }
        if (stock == 0) {
            return "Out of stock";
        }
        return stock <= 5 ? "Only " + stock + " left" : stock + " in stock";
    }

    public String priceLabel() {
        return pricePaise == null ? "Ask for price" : Money.label(pricePaise);
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
