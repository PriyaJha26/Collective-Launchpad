package com.collectivelaunchpad.tenant;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Shop addresses must be valid hostname labels and must not collide with platform or mail names. */
public final class SlugRules {

    private SlugRules() {}

    /** 2 to 30 characters, lowercase Latin letters, digits and hyphens, starts with a letter, no trailing hyphen. */
    private static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]{0,28}[a-z0-9]$");

    public static final Set<String> RESERVED = Set.of(
            "www", "admin", "api", "app", "mail", "email", "webmail", "smtp", "imap", "pop", "pop3", "mx",
            "hello", "info", "contact", "support", "help", "noreply", "no-reply", "postmaster", "abuse",
            "ftp", "ns1", "ns2", "autodiscover", "autoconfig", "login", "internal", "static", "assets",
            "uploads", "shop", "shops", "dashboard", "test", "staging", "dev");

    /** Returns empty when valid, otherwise a short reason. */
    public static Optional<String> problem(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.of("The address is empty.");
        }
        if (!SLUG.matcher(slug).matches()) {
            return Optional.of("Use 2 to 30 lowercase Latin letters, digits or hyphens, starting with a letter and not ending with a hyphen.");
        }
        if (slug.contains("--")) {
            return Optional.of("Do not use two hyphens in a row.");
        }
        if (RESERVED.contains(slug)) {
            return Optional.of("\"" + slug + "\" is reserved for the platform or email.");
        }
        return Optional.empty();
    }

    public static boolean isValid(String slug) {
        return problem(slug).isEmpty();
    }

    /** Lowercase, trim, spaces and underscores to hyphens. Does not transliterate other scripts. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-").replaceAll("-{2,}", "-");
    }
}
