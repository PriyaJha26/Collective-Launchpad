package com.collectivelaunchpad.account;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Slows down password guessing: after 10 failed logins from one IP in 15 minutes, login is refused for a while. */
@Component
public class LoginThrottle extends OncePerRequestFilter {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public void recordFailure(String ip) {
        Deque<Instant> q = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(Instant.now());
            prune(q);
        }
    }

    public void reset(String ip) {
        failures.remove(ip);
    }

    boolean blocked(String ip) {
        Deque<Instant> q = failures.get(ip);
        if (q == null) {
            return false;
        }
        synchronized (q) {
            prune(q);
            return q.size() >= MAX_FAILURES;
        }
    }

    private static void prune(Deque<Instant> q) {
        Instant cutoff = Instant.now().minus(WINDOW);
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
            q.pollFirst();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !("POST".equals(req.getMethod()) && "/login".equals(req.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (blocked(req.getRemoteAddr())) {
            res.sendRedirect("/login?locked");
            return;
        }
        chain.doFilter(req, res);
    }
}
