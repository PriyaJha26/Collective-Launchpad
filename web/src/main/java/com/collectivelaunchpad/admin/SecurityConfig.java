package com.collectivelaunchpad.admin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import com.collectivelaunchpad.account.AccountService;
import com.collectivelaunchpad.account.AppUser;
import com.collectivelaunchpad.account.GoogleSignIn;
import com.collectivelaunchpad.account.LoginThrottle;
import com.collectivelaunchpad.account.SessionLogin;
import com.collectivelaunchpad.domain.Account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Three roles: SUPERADMIN (/admin), SELLER (/seller), CUSTOMER (/account, checkout).
 * Browsing shops and products needs no login.
 */
@Configuration
public class SecurityConfig {

    /** No inline scripts or styles anywhere; photos may be previewed from blob: URLs before upload. */
    static final String CSP = String.join("; ",
            "default-src 'self'",
            "img-src 'self' data: blob:",
            "script-src 'self'",
            "style-src 'self'",
            "font-src 'self'",
            "connect-src 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "object-src 'none'");

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LoginThrottle throttle, AccountService accounts,
                                    ObjectProvider<ClientRegistrationRepository> google,
                                    GoogleSignIn googleSignIn, SessionLogin sessionLogin) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/admin/**").hasRole("SUPERADMIN")
                .requestMatchers("/seller/**").hasRole("SELLER")
                .requestMatchers("/account/**", "/checkout/**").authenticated()
                .anyRequest().permitAll())
            .formLogin(f -> f
                .loginPage("/login")
                .usernameParameter("login")
                .successHandler((req, res, auth) -> {
                    throttle.reset(req.getRemoteAddr());
                    AppUser user = AppUser.from(auth);
                    if (user != null) {
                        accounts.recordLogin(user.id());
                    }
                    goOn(req, res, auth, user);
                })
                .failureHandler((req, res, ex) -> {
                    throttle.recordFailure(req.getRemoteAddr());
                    res.sendRedirect(ex instanceof org.springframework.security.authentication.DisabledException
                            ? "/login?disabled" : "/login?error");
                })
                .permitAll())
            .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout"))
            .addFilterBefore(throttle, UsernamePasswordAuthenticationFilter.class)
            // /internal/* is service-to-service (token-protected, blocked by Caddy from the internet).
            // /contact, /contact-us and /join are public, anonymous forms: they change nothing a logged-in
            // user owns, and are guarded by validation, a honeypot field and a per-IP rate limit instead.
            .csrf(c -> c.ignoringRequestMatchers("/internal/**", "/contact", "/contact-us", "/join"))
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        if (google.getIfAvailable() != null) {
            http.oauth2Login(o -> o
                .loginPage("/login")
                .successHandler((req, res, auth) -> googleSuccess(req, res, auth, googleSignIn, sessionLogin, throttle))
                .failureHandler((req, res, ex) -> res.sendRedirect("/login?google=failed")));
        }
        return http.build();
    }

    /**
     * Google confirmed who this is; now find or create their account and log that in instead of
     * the raw Google identity, so the rest of the app sees the same AppUser as for a password login.
     */
    private static void googleSuccess(HttpServletRequest req, HttpServletResponse res, Authentication auth,
                                      GoogleSignIn googleSignIn, SessionLogin sessionLogin, LoginThrottle throttle)
            throws java.io.IOException {
        if (!(auth.getPrincipal() instanceof OidcUser g)) {
            forgetLogin(req, res);
            res.sendRedirect("/login?google=failed");
            return;
        }
        Account a;
        try {
            a = googleSignIn.signIn(new GoogleSignIn.GoogleUser(g.getSubject(), g.getEmail(),
                    Boolean.TRUE.equals(g.getEmailVerified()), g.getFullName()));
        } catch (GoogleSignIn.Refused r) {
            forgetLogin(req, res);
            res.sendRedirect("/login?google=" + switch (r.reason) {
                case SUPERADMIN -> "admin";
                case EMAIL_NOT_VERIFIED -> "unverified";
                case OTHER_GOOGLE_ACCOUNT -> "other";
            });
            return;
        }
        if (!a.active) {
            forgetLogin(req, res);
            res.sendRedirect("/login?disabled");
            return;
        }
        throttle.reset(req.getRemoteAddr());
        AppUser user = sessionLogin.logIn(a, req, res);
        goOn(req, res, SecurityContextHolder.getContext().getAuthentication(), user);
    }

    /** Drops the Google identity Spring already stored for this session (the cart stays). */
    private static void forgetLogin(HttpServletRequest req, HttpServletResponse res) {
        SecurityContextHolder.clearContext();
        new HttpSessionSecurityContextRepository().saveContext(SecurityContextHolder.createEmptyContext(), req, res);
    }

    /** Back to the page that asked for a login (e.g. checkout), else each role's home. */
    private static void goOn(HttpServletRequest req, HttpServletResponse res, Authentication auth, AppUser user)
            throws java.io.IOException {
        if (new HttpSessionRequestCache().getRequest(req, res) != null) {
            try {
                new SavedRequestAwareAuthenticationSuccessHandler().onAuthenticationSuccess(req, res, auth);
            } catch (jakarta.servlet.ServletException e) {
                throw new java.io.IOException(e);
            }
            return;
        }
        res.sendRedirect(user != null && user.isSuperadmin() ? "/admin"
                : user != null && user.isSeller() ? "/seller" : "/account");
    }

    /** The throttle runs inside the security chain only, not a second time as a plain servlet filter. */
    @Bean
    FilterRegistrationBean<LoginThrottle> loginThrottleRegistration(LoginThrottle throttle) {
        var bean = new FilterRegistrationBean<>(throttle);
        bean.setEnabled(false);
        return bean;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
