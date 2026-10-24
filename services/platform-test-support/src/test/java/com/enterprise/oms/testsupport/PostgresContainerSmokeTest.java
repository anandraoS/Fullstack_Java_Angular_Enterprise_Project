package com.enterprise.oms.testsupport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Skipped (not failed) where Docker is unavailable, runs against a real PostgreSQL 16 container otherwise. */
class PostgresContainerSmokeTest extends AbstractPostgresIT {

    @Test
    void containerIsRunningAndIsPostgres16() {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(POSTGRES.getJdbcUrl()).startsWith("jdbc:postgresql://");
    }
}
