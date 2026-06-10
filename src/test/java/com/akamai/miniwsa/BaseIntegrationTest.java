package com.akamai.miniwsa;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton-container pattern: one Postgres for the whole JVM, shared
 * by every subclass. {@code @Container}/{@code @Testcontainers} would
 * give a class-scoped lifecycle that stops the container between test
 * classes — but Spring's TestContext cache keeps the app context alive,
 * so the second class reuses a Hikari DataSource pointing at a dead port.
 * A static-init start avoids that: container lives until JVM exit and
 * Testcontainers' Ryuk reaper cleans it up.
 */
public abstract class BaseIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("miniwsa")
                    .withUsername("miniwsa")
                    .withPassword("miniwsa");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
