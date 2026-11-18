package com.collectivelaunchpad.tenant;

import java.io.IOException;
import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import com.collectivelaunchpad.config.SiteUrls;
import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Classifies every request by its Host header before security and controllers run:
 * main domain -> group site and admin; www -> redirect; published shop -> that shop's pages only;
 * anything else -> 404. Internal endpoints are reached over the private Docker network by
 * service name, so they skip host classification (Caddy blocks /internal/* from the internet).
 */
public class TenantFilter extends OncePerRequestFilter {

    /** The only paths a shop hostname serves. Everything else (admin, login) is main-domain only. */
    private static final List<String> SHOP_PATH_PREFIXES = List.of("/css/", "/js/", "/uploads/");
    private static final List<String> SHOP_PATHS = List.of("/", "/contact", "/favicon.ico", "/robots.txt");

    private final HostClassifier classifier;
    private final SellerRepository sellers;
    private final SiteUrls urls;

    public TenantFilter(HostClassifier classifier, SellerRepository sellers, SiteUrls urls) {
        this.classifier = classifier;
        this.sellers = sellers;
        this.urls = urls;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (path.startsWith("/internal/")) {
            chain.doFilter(req, res);
            return;
        }
        HostClassifier.Result host = classifier.classify(req.getServerName());
        switch (host.kind()) {
            case MAIN -> {
                req.setAttribute(Tenant.ATTRIBUTE, Tenant.MAIN);
                chain.doFilter(req, res);
            }
            case WWW -> {
                String query = req.getQueryString();
                res.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
                res.setHeader("Location", urls.mainUrl() + path + (query == null ? "" : "?" + query));
            }
            case SHOP -> {
                if (!SHOP_PATHS.contains(path) && SHOP_PATH_PREFIXES.stream().noneMatch(path::startsWith)) {
                    res.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                var seller = sellers.findBySlugAndStatus(host.slug(), SellerStatus.PUBLISHED);
                if (seller.isEmpty()) {
                    res.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                req.setAttribute(Tenant.ATTRIBUTE, new Tenant(seller.get()));
                chain.doFilter(req, res);
            }
            default -> res.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @Configuration
    static class Registration {
        @Bean
        FilterRegistrationBean<TenantFilter> tenantFilter(HostClassifier classifier, SellerRepository sellers, SiteUrls urls) {
            var bean = new FilterRegistrationBean<>(new TenantFilter(classifier, sellers, urls));
            bean.setOrder(-110); // before Spring Security (-100)
            bean.addUrlPatterns("/*");
            return bean;
        }
    }
}
