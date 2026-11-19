package com.collectivelaunchpad.i18n;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/** Links that switch the current page to another language (the language picker and banner). */
public record LangLinks(String path, String query) {

    public static LangLinks of(HttpServletRequest req) {
        // After a POST (e.g. a form shown again with an error) the same URL may not take a GET: go home.
        if (!"GET".equals(req.getMethod())) {
            return new LangLinks("/", "");
        }
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (!path.startsWith("/") || path.startsWith("//")) {
            path = "/";
        }
        List<String> kept = new ArrayList<>();
        String q = req.getQueryString();
        if (q != null) {
            for (String part : q.split("&")) {
                String name = URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8);
                if (!part.isEmpty() && !"lang".equals(name)) {
                    kept.add(part);
                }
            }
        }
        return new LangLinks(path, String.join("&", kept));
    }

    /** Same page, in another language. */
    public String href(String code) {
        String lang = "lang=" + URLEncoder.encode(Languages.orEnglish(code), StandardCharsets.UTF_8);
        return path + "?" + (query.isEmpty() ? "" : query + "&") + lang;
    }
}
