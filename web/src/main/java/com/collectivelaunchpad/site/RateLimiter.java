package com.collectivelaunchpad.site;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** In-memory sliding window per key (client IP). Enough for one server. */
@Component
public class RateLimiter {

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimiter(@Value("${launchpad.contact-rate-limit:5}") int limit,
                       @Value("${launchpad.contact-rate-window-minutes:10}") int windowMinutes) {
        this(limit, Duration.ofMinutes(windowMinutes), Clock.systemUTC());
    }

    RateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    /** Records a hit and returns true if the key is still within its limit. */
    public boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Deque<Instant> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(window))) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                return false;
            }
            q.addLast(now);
        }
        if (hits.size() > 10_000) {
            hits.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        return true;
    }

    public void reset() {
        hits.clear();
    }
}
