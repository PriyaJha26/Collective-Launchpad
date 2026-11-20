package com.collectivelaunchpad.web;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts the whole app against a real PostgreSQL: a Testcontainers database when Docker is
 * available, otherwise the database in TEST_DB_URL (e.g. a local test database). Without
 * either, the tests are skipped rather than failing.
 */
@SpringBootTest(properties = {
        "DOMAIN=example.test",
        "INTERNAL_TOKEN=test-token",
        "MAIL_FROM=hello@example.test",
        "ADMIN_EMAIL=admin@example.test",
        "ADMIN_PASSWORD=correct-horse-battery",
        "SEED_SAMPLE_SHOP=false",
})
abstract class IntegrationTestBase {

    static PostgreSQLContainer<?> postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("TEST_DB_URL");
        boolean docker = isDockerAvailable();
        if (docker && postgres == null) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
        }
        Assumptions.assumeTrue(docker || url != null, "Needs Docker or TEST_DB_URL");
        if (docker) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "launchpad"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
            // Start from an empty schema every run.
            registry.add("spring.flyway.clean-disabled", () -> "false");
            registry.add("spring.flyway.clean-on-validation-error", () -> "false");
        }
        Path uploads = Files.createTempDirectory("launchpad-test-uploads");
        registry.add("launchpad.uploads-dir", uploads::toString);
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
