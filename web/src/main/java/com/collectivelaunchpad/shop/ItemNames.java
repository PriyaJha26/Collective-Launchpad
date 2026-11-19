package com.collectivelaunchpad.shop;

import java.util.Map;

import com.collectivelaunchpad.domain.OrderItem;
import com.collectivelaunchpad.domain.Product;

/** Order lines keep the name copied at order time; shown in the reader's language while the product still exists. */
public record ItemNames(Map<Long, Product> products) {

    public String name(OrderItem it, String lang) {
        Product p = it.productId == null ? null : products.get(it.productId);
        return p != null && p.name.equals(it.name) ? p.nameIn(lang) : it.name;
    }

    public String unit(OrderItem it, String lang) {
        Product p = it.productId == null ? null : products.get(it.productId);
        return p != null && java.util.Objects.equals(p.unit, it.unit) ? p.unitIn(lang) : it.unit;
    }
}
