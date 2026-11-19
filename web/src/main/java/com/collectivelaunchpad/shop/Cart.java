package com.collectivelaunchpad.shop;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpSession;

/** The shopping cart, kept in the visitor's session (no login needed to fill it). productId -> quantity. */
public class Cart implements Serializable {

    public static final int MAX_QTY = 100;
    public static final int MAX_LINES = 50;
    private static final String KEY = Cart.class.getName();

    private final Map<Long, Integer> lines = new LinkedHashMap<>();

    public static Cart of(HttpSession session) {
        Object c = session.getAttribute(KEY);
        if (c instanceof Cart cart) {
            return cart;
        }
        Cart cart = new Cart();
        session.setAttribute(KEY, cart);
        return cart;
    }

    /** Read-only peek that does not create a session. */
    public static int count(HttpSession session) {
        if (session == null) {
            return 0;
        }
        Object c = session.getAttribute(KEY);
        return c instanceof Cart cart ? cart.lines.values().stream().mapToInt(Integer::intValue).sum() : 0;
    }

    public synchronized void add(long productId, int qty) {
        if (!lines.containsKey(productId) && lines.size() >= MAX_LINES) {
            return;
        }
        lines.merge(productId, Math.max(1, qty), (a, b) -> Math.min(MAX_QTY, a + b));
    }

    public synchronized void set(long productId, int qty) {
        if (qty <= 0) {
            lines.remove(productId);
        } else if (lines.containsKey(productId)) {
            lines.put(productId, Math.min(MAX_QTY, qty));
        }
    }

    public synchronized void remove(long productId) {
        lines.remove(productId);
    }

    public synchronized Map<Long, Integer> lines() {
        return new LinkedHashMap<>(lines);
    }

    public synchronized boolean isEmpty() {
        return lines.isEmpty();
    }
}
