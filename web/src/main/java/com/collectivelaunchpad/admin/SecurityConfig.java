package com.collectivelaunchpad.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import com.collectivelaunchpad.domain.Repositories.AdminRepository;

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
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll())
            .formLogin(f -> f
                .loginPage("/login")
                .usernameParameter("email")
                .defaultSuccessUrl("/admin", true)
                .failureUrl("/login?error")
                .permitAll())
            .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout"))
            // /internal/* is service-to-service (token-protected, blocked by Caddy from the internet).
            // /contact is the public, anonymous shop contact form: it has no session to protect and is
            // guarded by validation, a honeypot field and a per-IP rate limit instead.
            .csrf(c -> c.ignoringRequestMatchers("/internal/**", "/contact"))
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AdminRepository admins) {
        return email -> admins.findByEmailIgnoreCase(email.strip())
                .map(a -> User.withUsername(a.email).password(a.passwordHash).roles("ADMIN").build())
                .orElseThrow(() -> new UsernameNotFoundException("No admin " + email));
    }
}
