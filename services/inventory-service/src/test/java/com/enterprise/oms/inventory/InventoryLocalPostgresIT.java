package com.enterprise.oms.inventory;

import com.enterprise.oms.inventory.repo.ProductQueryRepository;
import com.enterprise.oms.inventory.repo.ReservationClaims;
import com.enterprise.oms.inventory.reservation.ReservationStrategies;
import com.enterprise.oms.inventory.service.ReservationLifecycleService;
import com.enterprise.oms.inventory.service.ReservationService;
import com.enterprise.oms.inventory.service.StockService;
import com.enterprise.oms.testsupport.JwtTestTokens;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link PostgresScenarios} against ANY reachable PostgreSQL (for machines without Docker). Enabled only when
 * {@code OMS_TEST_PG_URL} is set (e.g. {@code jdbc:postgresql://127.0.0.1:5432/inventory_test}); credentials come from
 * {@code OMS_TEST_PG_USER} / {@code OMS_TEST_PG_PASSWORD}. The target database must be EMPTY (Flyway creates the
 * schema) and disposable. Never point this at a shared or production database.
 * <pre>
 * OMS_TEST_PG_URL=... OMS_TEST_PG_USER=... OMS_TEST_PG_PASSWORD=... mvn -pl inventory-service verify -Dit.test=InventoryLocalPostgresIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "OMS_TEST_PG_URL", matches = "jdbc:postgresql://.+")
@SpringBootTest(properties = {
        "oms.inventory.listeners-enabled=false",
        "oms.inventory.expiry-scheduler-enabled=false",
        "oms.outbox.relay-enabled=false"})
class InventoryLocalPostgresIT implements PostgresScenarios {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("OMS_TEST_PG_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("OMS_TEST_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("OMS_TEST_PG_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        JwtTestTokens.registerProperties(registry);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationService reservations;
    @Autowired ReservationLifecycleService lifecycle;
    @Autowired ReservationStrategies strategies;
    @Autowired ReservationClaims claims;
    @Autowired ProductQueryRepository query;
    @Autowired StockService stockService;
    @Autowired PlatformTransactionManager txManager;
    @Autowired com.enterprise.oms.inventory.metrics.InventoryMetrics metrics;

    @Override public JdbcTemplate jdbc() { return jdbc; }
    @Override public ReservationService reservations() { return reservations; }
    @Override public ReservationLifecycleService lifecycle() { return lifecycle; }
    @Override public ReservationStrategies strategies() { return strategies; }
    @Override public ReservationClaims claims() { return claims; }
    @Override public ProductQueryRepository query() { return query; }
    @Override public StockService stockService() { return stockService; }
    @Override public PlatformTransactionManager txManager() { return txManager; }
    @Override public com.enterprise.oms.inventory.metrics.InventoryMetrics metrics() { return metrics; }
}
