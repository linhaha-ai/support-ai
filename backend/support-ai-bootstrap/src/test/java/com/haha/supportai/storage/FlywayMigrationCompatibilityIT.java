package com.haha.supportai.storage;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayMigrationCompatibilityIT {

    private static final int TICKET_MIGRATION_CHECKSUM = -706768438;
    private static final int TOOL_CALL_MIGRATION_CHECKSUM = -1533669448;
    private static final int CLEANUP_MIGRATION_CHECKSUM = -326228586;

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @Test
    void upgradesExistingV14SchemaWithoutRepairingMigrationHistory() {
        Flyway existingMigrations = Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("14"))
                .load();

        assertThat(existingMigrations.migrate().migrationsExecuted).isEqualTo(14);

        JdbcTemplate jdbc = PostgresFixture.jdbc(DATABASE);
        assertThat(migrationChecksums(jdbc)).containsExactly(
                TICKET_MIGRATION_CHECKSUM,
                TOOL_CALL_MIGRATION_CHECKSUM,
                CLEANUP_MIGRATION_CHECKSUM);
        assertThat(relationExists(jdbc, "ticket")).isTrue();
        assertThat(relationExists(jdbc, "tool_call")).isTrue();
        assertThat(relationExists(jdbc, "document_cleanup_job")).isTrue();
        assertThat(columnExists(jdbc, "chat_run", "trace_id")).isFalse();
        assertThat(columnExists(jdbc, "ingestion_job", "trace_id")).isFalse();

        Flyway currentMigrations = PostgresFixture.migrations(DATABASE, false);
        assertThat(currentMigrations.migrate().migrationsExecuted).isOne();
        currentMigrations.validate();

        assertThat(migrationDescriptions(jdbc)).containsExactly(
                "12:create ticket",
                "13:create tool call and ticket source",
                "14:create document cleanup job",
                "15:add failure diagnostics");
        assertThat(migrationChecksums(jdbc)).hasSize(4).startsWith(
                TICKET_MIGRATION_CHECKSUM,
                TOOL_CALL_MIGRATION_CHECKSUM,
                CLEANUP_MIGRATION_CHECKSUM);
        assertThat(relationExists(jdbc, "document_cleanup_job")).isTrue();
        assertThat(columnExists(jdbc, "chat_run", "trace_id")).isTrue();
        assertThat(columnExists(jdbc, "ingestion_job", "trace_id")).isTrue();
    }

    private List<Integer> migrationChecksums(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT checksum
                FROM flyway_schema_history
                WHERE version IN ('12', '13', '14', '15')
                ORDER BY installed_rank
                """, Integer.class);
    }

    private List<String> migrationDescriptions(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT version || ':' || description
                FROM flyway_schema_history
                WHERE version IN ('12', '13', '14', '15')
                ORDER BY installed_rank
                """, String.class);
    }

    private boolean relationExists(JdbcTemplate jdbc, String tableName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('public.' || ?) IS NOT NULL", Boolean.class, tableName));
    }

    private boolean columnExists(JdbcTemplate jdbc, String tableName, String columnName) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = ?
                      AND column_name = ?
                )
                """, Boolean.class, tableName, columnName));
    }
}
