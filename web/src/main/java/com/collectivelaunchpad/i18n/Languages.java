package com.collectivelaunchpad.i18n;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** The 12 languages the website speaks, and helpers to read stored translations. */
public final class Languages {

    private Languages() {}

    /** Code -> the language's own name, in the order shown in the language picker. */
    public static final Map<String, String> NATIVE = Collections.unmodifiableMap(new LinkedHashMap<>() {{
        put("en", "English");
        put("hi", "हिन्दी");
        put("mr", "मराठी");
        put("bn", "বাংলা");
        put("ta", "தமிழ்");
        put("te", "తెలుగు");
        put("kn", "ಕನ್ನಡ");
        put("ml", "മലയാളം");
        put("gu", "ગુજરાતી");
        put("pa", "ਪੰਜਾਬੀ");
        put("or", "ଓଡ଼ିଆ");
        put("ur", "اردو");
    }});

    public static final List<String> CODES = List.copyOf(NATIVE.keySet());

    public static boolean isSupported(String code) {
        return code != null && NATIVE.containsKey(code);
    }

    /** A supported code, or English. */
    public static String orEnglish(String code) {
        return isSupported(code) ? code : "en";
    }

    public static String of(Locale locale) {
        return locale == null ? "en" : orEnglish(locale.getLanguage());
    }

    public static boolean isRtl(String code) {
        return "ur".equals(code);
    }

    /** Every language except the given one (e.g. the one a text was written in). */
    public static List<String> allBut(String code) {
        return CODES.stream().filter(c -> !c.equals(code)).toList();
    }

    /** translations[lang][field], or the fallback when there is none. */
    public static String pick(JsonNode translations, String lang, String field, String fallback) {
        if (translations == null || lang == null) {
            return fallback;
        }
        JsonNode v = translations.path(lang).path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : fallback;
    }

    /** translations[lang], or the fallback when there is none. */
    public static String pick(JsonNode translations, String lang, String fallback) {
        if (translations == null || lang == null) {
            return fallback;
        }
        JsonNode v = translations.path(lang);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : fallback;
    }

    public static boolean has(JsonNode translations, String lang) {
        return translations != null && lang != null && translations.path(lang).isTextual();
    }
}
