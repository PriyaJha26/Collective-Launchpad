package com.collectivelaunchpad.tenant;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.collectivelaunchpad.config.SiteUrls;

/** Classifies a request's Host header against the group's domain. */
@Component
public class HostClassifier {

    public enum Kind { MAIN, WWW, SHOP, UNKNOWN }

    public record Result(Kind kind, String slug) {
        static Result of(Kind kind) {
            return new Result(kind, null);
        }
    }

    private final String domain;

    @org.springframework.beans.factory.annotation.Autowired
    public HostClassifier(SiteUrls urls) {
        this(urls.domain());
    }

    HostClassifier(String domain) {
        this.domain = domain.toLowerCase(Locale.ROOT);
    }

    public Result classify(String host) {
        if (host == null || host.isBlank()) {
            return Result.of(Kind.UNKNOWN);
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1); // fully qualified form "example.in."
        }
        if (h.equals(domain)) {
            return Result.of(Kind.MAIN);
        }
        if (h.equals("www." + domain)) {
            return Result.of(Kind.WWW);
        }
        String suffix = "." + domain;
        if (!h.endsWith(suffix)) {
            return Result.of(Kind.UNKNOWN); // foreign host
        }
        String label = h.substring(0, h.length() - suffix.length());
        if (label.contains(".") || !SlugRules.isValid(label)) {
            return Result.of(Kind.UNKNOWN); // nested or not a valid shop address
        }
        return new Result(Kind.SHOP, label);
    }
}
