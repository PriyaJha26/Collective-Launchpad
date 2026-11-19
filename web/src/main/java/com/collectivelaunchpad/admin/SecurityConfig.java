package com.collectivelaunchpad.admin;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import com.collectivelaunchpad.account.AccountService;
import com.collectivelaunchpad.account.AppUser;
import com.collectivelaunchpad.account.LoginThrottle;

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
                                    com.collectivelaunchpad.i18n.LanguageResolver languages) throws Exception {
        var afterLogin = new SavedRequestAwareAuthenticationSuccessHandler() {
            @Override
            protected String determineTargetUrl(jakarta.servlet.http.HttpServletRequest request,
                                                jakarta.servlet.http.HttpServletResponse response) {
                return "/";
            }
        };
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
                        languages.onLogin(req, res, user.id()); // show the site in their saved language
                    }
                    // Back to the page that asked for a login (e.g. checkout), else each role's home.
                    var saved = new org.springframework.security.web.savedrequest.HttpSessionRequestCache().getRequest(req, res);
                    if (saved != null) {
                        afterLogin.onAuthenticationSuccess(req, res, auth);
                    } else {
                        res.sendRedirect(user != null && user.isSuperadmin() ? "/admin"
                                : user != null && user.isSeller() ? "/seller" : "/account");
                    }
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
        return http.build();
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
