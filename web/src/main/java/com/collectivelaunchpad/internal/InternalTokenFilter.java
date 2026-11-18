package com.collectivelaunchpad.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import com.collectivelaunchpad.config.AppProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** The agent's tool endpoints require the shared service token. With no token configured, all are refused. */
public class InternalTokenFilter extends OncePerRequestFilter {

    private final byte[] token;

    public InternalTokenFilter(String token) {
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/internal/agent/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader("X-Internal-Token");
        if (token == null || given == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"missing or wrong internal token\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    @Configuration
    static class Registration {
        @Bean
        FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(AppProperties props) {
            var bean = new FilterRegistrationBean<>(new InternalTokenFilter(props.internalToken()));
            bean.setOrder(-105);
            bean.addUrlPatterns("/internal/agent/*");
            return bean;
        }
    }
}
