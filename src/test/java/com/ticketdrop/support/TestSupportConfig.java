package com.ticketdrop.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfig {

    /**
     * One PostgreSQL for the whole test run. Test classes with different settings get different
     * Spring contexts, and all of them share this container, so tests must never assume empty tables.
     */
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    /** Lazy, because the port is only known once the server has started. */
    @Bean
    @Lazy
    ApiClient apiClient(@Value("${local.server.port}") int port) {
        return new ApiClient(port);
    }

    @Bean
    @Lazy
    TestData testData(JdbcClient jdbc) {
        return new TestData(jdbc);
    }

    @Bean
    @Lazy
    Invariants invariants(JdbcClient jdbc) {
        return new Invariants(jdbc);
    }
}
