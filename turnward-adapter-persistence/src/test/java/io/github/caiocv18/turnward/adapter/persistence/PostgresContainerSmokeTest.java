package io.github.caiocv18.turnward.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Smoke test for Testcontainers 2 on this machine: the pgvector image starts, and it carries the same Postgres
 * major and pgvector version as the RDS instance (design note, section 11).
 */
@Testcontainers
class PostgresContainerSmokeTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresImage.NAME);

    @Test
    void runsPostgres18WithPgvector082() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create extension if not exists vector");

            assertThat(single(statement, "show server_version")).startsWith("18.");
            assertThat(single(statement, "select extversion from pg_extension where extname = 'vector'"))
                    .isEqualTo("0.8.2");
        }
    }

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
