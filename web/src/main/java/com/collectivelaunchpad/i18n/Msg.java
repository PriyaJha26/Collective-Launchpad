package com.collectivelaunchpad.i18n;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/** Website texts from i18n/messages.properties for Java code (flash notices, form errors, emails). */
@Component
public class Msg {

    private final MessageSource messages;

    public Msg(MessageSource messages) {
        this.messages = messages;
    }

    public String get(String key, Object... args) {
        return messages.getMessage(key, args, Locale.ENGLISH);
    }
}
