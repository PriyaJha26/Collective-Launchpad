package com.collectivelaunchpad.config;

import java.util.Locale;

import org.springframework.stereotype.Component;

/** Builds the public URLs of the group page and the shops. */
@Component
public class SiteUrls {

    private final AppProperties.Site site;

    public SiteUrls(AppProperties props) {
        this.site = props.site();
    }

    public String domain() {
        return site.domain().toLowerCase(Locale.ROOT);
    }

    public boolean isLocal() {
        return "localhost".equals(domain());
    }

    public int port() {
        return site.port();
    }

    private String portSuffix() {
        int p = site.port();
        return p > 0 && p != 80 && p != 443 ? ":" + p : "";
    }

    public String mainUrl() {
        return site.scheme() + "://" + domain() + portSuffix();
    }

    public String shopUrl(String slug) {
        return site.scheme() + "://" + slug + "." + domain() + portSuffix() + "/";
    }

    /** "meena.ourvillagecrafts.in", for display. */
    public String shopHost(String slug) {
        return slug + "." + domain();
    }
}
