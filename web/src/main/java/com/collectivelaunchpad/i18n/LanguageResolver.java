package com.collectivelaunchpad.i18n;

import java.time.Duration;
import java.util.Collections;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.LocaleResolver;

import com.collectivelaunchpad.account.AppUser;
import com.collectivelaunchpad.config.SiteUrls;
import com.collectivelaunchpad.domain.Repositories.AccountRepository;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Which language a page is shown in:
 * superadmin pages are always English; otherwise the logged-in person's saved language (cached in
 * the session at login), else the LAUNCHPAD_LANG cookie, else the browser's Accept-Language if it
 * is one of ours, else English. Choosing a language (?lang=xx) sets the cookie for the main domain
 * and every shop subdomain, and saves it on the account when logged in.
 */
public class LanguageResolver implements LocaleResolver {

    public static final String COOKIE = "LAUNCHPAD_LANG";
    public static final String SESSION_ATTRIBUTE = "LAUNCHPAD_USER_LANG";
    private static final String REQUEST_ATTRIBUTE = LanguageResolver.class.getName() + ".chosen";

    private final SiteUrls urls;
    private final AccountRepository accounts;

    public LanguageResolver(SiteUrls urls, AccountRepository accounts) {
        this.urls = urls;
        this.accounts = accounts;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest req) {
        if (isAdminPath(req)) {
            return Locale.ENGLISH;
        }
        String chosen = chosen(req);
        return Locale.forLanguageTag(chosen != null ? chosen : fromBrowser(req));
    }

    /** ?lang=xx: remember the choice. Unsupported codes are ignored. */
    @Override
    public void setLocale(HttpServletRequest req, HttpServletResponse res, Locale locale) {
        String code = locale == null ? null : locale.getLanguage();
        if (!Languages.isSupported(code) || isAdminPath(req)) {
            return;
        }
        remember(req, res, code);
        AppUser me = AppUser.from(SecurityContextHolder.getContext().getAuthentication());
        if (me != null) {
            accounts.findById(me.id()).filter(a -> !code.equals(a.language)).ifPresent(a -> {
                a.language = code;
                accounts.save(a);
            });
        }
    }

    /**
     * After logging in: the account's saved language wins; an account without one keeps the
     * language the person was already browsing in.
     */
    public void onLogin(HttpServletRequest req, HttpServletResponse res, long accountId) {
        accounts.findById(accountId).ifPresent(a -> {
            if (Languages.isSupported(a.language)) {
                remember(req, res, a.language);
            } else {
                String browsing = cookie(req);
                if (browsing != null) {
                    a.language = browsing;
                    accounts.save(a);
                    remember(req, res, browsing);
                }
            }
        });
    }

    /** True once the person has picked a language (no need to show the banner). */
    public boolean hasChosen(HttpServletRequest req) {
        return chosen(req) != null;
    }

    // ------------------------------------------------------------------ helpers

    private void remember(HttpServletRequest req, HttpServletResponse res, String code) {
        req.setAttribute(REQUEST_ATTRIBUTE, code);
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.setAttribute(SESSION_ATTRIBUTE, code);
        }
        if (res != null) {
            ResponseCookie.ResponseCookieBuilder c = ResponseCookie.from(COOKIE, code)
                    .path("/")
                    .maxAge(Duration.ofDays(365))
                    .httpOnly(true)
                    .secure(req.isSecure())
                    .sameSite("Lax");
            if (!urls.isLocal()) {
                c.domain(urls.domain()); // shared with the shop subdomains; holds only a language code
            }
            res.addHeader(HttpHeaders.SET_COOKIE, c.build().toString());
        }
    }

    private String chosen(HttpServletRequest req) {
        Object now = req.getAttribute(REQUEST_ATTRIBUTE);
        if (now instanceof String s) {
            return s;
        }
        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof String s && Languages.isSupported(s)) {
            return s;
        }
        return cookie(req);
    }

    private static String cookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName()) && Languages.isSupported(c.getValue())) {
                return c.getValue();
            }
        }
        return null;
    }

    private static String fromBrowser(HttpServletRequest req) {
        if (req.getHeader(HttpHeaders.ACCEPT_LANGUAGE) == null) {
            return "en";
        }
        for (Locale l : Collections.list(req.getLocales())) {
            if (Languages.isSupported(l.getLanguage())) {
                return l.getLanguage();
            }
        }
        return "en";
    }

    static boolean isAdminPath(HttpServletRequest req) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        return path.equals("/admin") || path.startsWith("/admin/");
    }
}
