package com.sentinelpay.api.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the Flyway-managed schema on a real PostgreSQL container. */
class FlywayMigrationIT extends AbstractIntegrationTest {

    @Value("${spring.jpa.hibernate.ddl-auto}")
    String ddlAuto;

    @Test
    void flywayAppliedBothMigrationsSuccessfully() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList("""
                SELECT version, description, success
                FROM flyway_schema_history
                WHERE version IS NOT NULL
                ORDER BY installed_rank
                """);

        assertThat(history).extracting(row -> row.get("version")).containsExactly("1", "2");
        assertThat(history).extracting(row -> row.get("description"))
                .containsExactly("Create initial schema", "Create users and roles");
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
    }

    @Test
    void migrationsCreatedEveryExpectedTable() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                """, String.class);

        assertThat(tables).contains("accounts", "transactions", "users", "user_roles");
    }

    @Test
    void hibernateValidatesEntityMappingsAgainstMigratedSchema() {
        // ddl-auto=validate runs during context startup, so an entity/schema mismatch would have
        // failed this test class before any assertion ran.
        assertThat(ddlAuto).isEqualTo("validate");
    }

    @Test
    void transactionsReferenceAccountsThroughForeignKey() {
        List<Map<String, Object>> foreignKeys = jdbcTemplate.queryForList("""
                SELECT tc.table_name, ccu.table_name AS referenced_table
                FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public'
                """);

        assertThat(foreignKeys)
                .anySatisfy(fk -> {
                    assertThat(fk.get("table_name")).isEqualTo("transactions");
                    assertThat(fk.get("referenced_table")).isEqualTo("accounts");
                })
                .anySatisfy(fk -> {
                    assertThat(fk.get("table_name")).isEqualTo("users");
                    assertThat(fk.get("referenced_table")).isEqualTo("accounts");
                })
                .anySatisfy(fk -> {
                    assertThat(fk.get("table_name")).isEqualTo("user_roles");
                    assertThat(fk.get("referenced_table")).isEqualTo("users");
                });
    }

    @Test
    void uniqueConstraintsProtectAccountNumberUsernameAndIdempotencyKey() {
        List<Map<String, Object>> unique = jdbcTemplate.queryForList("""
                SELECT tc.table_name, ccu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name
                WHERE tc.constraint_type = 'UNIQUE' AND tc.table_schema = 'public'
                """);

        assertThat(unique).anySatisfy(c -> {
            assertThat(c.get("table_name")).isEqualTo("accounts");
            assertThat(c.get("column_name")).isEqualTo("account_number");
        });
        assertThat(unique).anySatisfy(c -> {
            assertThat(c.get("table_name")).isEqualTo("users");
            assertThat(c.get("column_name")).isEqualTo("username");
        });
        assertThat(unique).anySatisfy(c -> {
            assertThat(c.get("table_name")).isEqualTo("transactions");
            assertThat(c.get("column_name")).isEqualTo("idempotency_key");
        });
    }
}
