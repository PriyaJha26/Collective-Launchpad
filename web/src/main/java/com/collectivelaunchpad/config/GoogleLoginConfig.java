package com.collectivelaunchpad.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * "Continue with Google", switched on by GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET. Without them
 * there is no client registration, so SecurityConfig skips Google login and the button is hidden.
 */
@Configuration
@ConditionalOnExpression("!'${launchpad.google.client-id:}'.isBlank() and !'${launchpad.google.client-secret:}'.isBlank()")
public class GoogleLoginConfig {

    public static final String REGISTRATION_ID = "google";

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(@Value("${launchpad.google.client-id}") String clientId,
                                                              @Value("${launchpad.google.client-secret}") String clientSecret) {
        // Redirect URI: {baseUrl}/login/oauth2/code/google (https://<domain>/... behind Caddy).
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder(REGISTRATION_ID)
                .clientId(clientId.strip())
                .clientSecret(clientSecret.strip())
                .scope("openid", "email", "profile")
                .build());
    }
}
