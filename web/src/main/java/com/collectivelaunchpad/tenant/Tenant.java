package com.collectivelaunchpad.tenant;

import com.collectivelaunchpad.domain.Seller;

import jakarta.servlet.http.HttpServletRequest;

/** What the current request's hostname points at: the group's main site, or one published shop. */
public record Tenant(Seller shop) {

    static final String ATTRIBUTE = Tenant.class.getName();
    static final Tenant MAIN = new Tenant(null);

    public boolean isShop() {
        return shop != null;
    }

    public static Tenant of(HttpServletRequest request) {
        Object t = request.getAttribute(ATTRIBUTE);
        return t instanceof Tenant tenant ? tenant : MAIN;
    }
}
