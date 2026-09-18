package com.haha.supportai.storage;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class PostgresFixture {

    public static final String IMAGE = "pgvector/pgvector:0.8.6-pg16-bookworm"
            + "@sha256:ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b";

    private PostgresFixture() {
    }

    public static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
    }

    public static Flyway migrations(PostgreSQLContainer<?> database, boolean includeTestTables) {
        var configuration = Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        return configuration.locations(includeTestTables
                ? new String[] { "classpath:db/migration", "classpath:db/test-migration" }
                : new String[] { "classpath:db/migration" }).load();
    }

    public static JdbcTemplate jdbc(PostgreSQLContainer<?> database) {
        return new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }
}
