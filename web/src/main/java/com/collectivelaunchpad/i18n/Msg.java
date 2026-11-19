package com.collectivelaunchpad.i18n;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Website texts from i18n/messages*.properties for Java code (flash notices, form errors,
 * emails). Uses the current request's language; superadmin pages always resolve to English.
 */
@Component
public class Msg {

    private final MessageSource messages;

    public Msg(MessageSource messages) {
        this.messages = messages;
    }

    public String get(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    /** In a given language, e.g. for an email to someone who is not the current reader. */
    public String in(String lang, String key, Object... args) {
        return messages.getMessage(key, args, Locale.forLanguageTag(Languages.orEnglish(lang)));
    }

    /** The current reader's language code. */
    public static String lang() {
        return Languages.of(LocaleContextHolder.getLocale());
    }
}
