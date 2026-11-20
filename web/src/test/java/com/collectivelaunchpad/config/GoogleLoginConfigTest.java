package com.collectivelaunchpad.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/** Google login is only switched on when both the client id and secret are set. */
class GoogleLoginConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GoogleLoginConfig.class);

    @Test
    void offWithoutCredentials() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ClientRegistrationRepository.class));
        runner.withPropertyValues("launchpad.google.client-id=abc", "launchpad.google.client-secret= ")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ClientRegistrationRepository.class));
    }

    @Test
    void onWithCredentials() {
        runner.withPropertyValues("launchpad.google.client-id= abc.apps.googleusercontent.com ", "launchpad.google.client-secret=shh")
                .run(ctx -> {
                    var google = ctx.getBean(ClientRegistrationRepository.class).findByRegistrationId("google");
                    assertThat(google.getClientId()).isEqualTo("abc.apps.googleusercontent.com");
                    assertThat(google.getScopes()).contains("openid", "email", "profile");
                    assertThat(google.getRedirectUri()).isEqualTo("{baseUrl}/{action}/oauth2/code/{registrationId}");
                });
    }
}
