package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import io.github.caiocv18.turnward.adapter.persistence.PostgresImage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Minimal Boot context for the event store tests: the real auto-configuration of DataSource, Flyway, JdbcClient and
 * transactions, on one pgvector container shared through the test context cache.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(EventStoreConfiguration.class)
class EventStoreTestApplication {

    // Nanoseconds on purpose: the store must truncate to what Postgres keeps.
    static final Instant NOW = Instant.parse("2026-10-20T12:34:56.123456789Z");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(PostgresImage.NAME);
    }

    @Bean
    Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
