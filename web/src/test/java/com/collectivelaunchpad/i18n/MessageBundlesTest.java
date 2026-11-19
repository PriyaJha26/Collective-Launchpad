package com.collectivelaunchpad.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every language has every English key, the same {n} arguments, and patterns MessageFormat can parse. */
class MessageBundlesTest {

    private static final Pattern ARG = Pattern.compile("\\{(\\d+)[,}]");

    @ParameterizedTest
    @ValueSource(strings = {"hi", "mr", "ta", "te", "kn", "ml", "bn", "gu", "pa", "or", "ur"})
    void bundleMatchesEnglish(String lang) throws IOException {
        Properties english = load("messages.properties");
        Properties bundle = load("messages_" + lang + ".properties");
        assertEquals(new TreeSet<>(english.stringPropertyNames()), new TreeSet<>(bundle.stringPropertyNames()),
                lang + ": keys differ from English (run scripts/translate_messages.py)");

        List<String> problems = new ArrayList<>();
        for (String key : english.stringPropertyNames()) {
            String en = english.getProperty(key), tr = bundle.getProperty(key);
            if (tr.isBlank()) problems.add(key + " is empty");
            if (!args(en).equals(args(tr))) problems.add(key + " uses different {n} arguments");
            if (!args(tr).isEmpty()) {
                try {
                    new MessageFormat(tr, Locale.forLanguageTag(lang)).format(new Object[] {1, "x", "y"});
                } catch (IllegalArgumentException e) {
                    problems.add(key + " is not a valid pattern: " + e.getMessage());
                }
                if (tr.contains("'") && !tr.contains("''")) problems.add(key + " has a single ' (use ’ or '')");
            }
        }
        assertTrue(problems.isEmpty(), lang + ": " + String.join("; ", problems));
    }

    private static Set<String> args(String pattern) {
        Set<String> out = new TreeSet<>();
        Matcher m = ARG.matcher(pattern);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Properties load(String name) throws IOException {
        Properties p = new Properties();
        try (InputStream in = MessageBundlesTest.class.getResourceAsStream("/i18n/" + name)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }
}
