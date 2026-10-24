package com.enterprise.oms.inventory;

import com.enterprise.oms.inventory.repo.ProductQueryRepository;
import com.enterprise.oms.inventory.repo.ReservationClaims;
import com.enterprise.oms.inventory.reservation.ReservationStrategies;
import com.enterprise.oms.inventory.service.ReservationLifecycleService;
import com.enterprise.oms.inventory.service.ReservationService;
import com.enterprise.oms.inventory.service.StockService;
import com.enterprise.oms.testsupport.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link PostgresScenarios} against a PostgreSQL 16 Testcontainer. Skipped (not failed) when Docker is not available
 * ({@code @Testcontainers(disabledWithoutDocker = true)} in {@link AbstractPostgresIT}); Flyway runs the production
 * migrations V1 + V10 on PostgreSQL.
 */
@SpringBootTest(properties = {
        "oms.inventory.listeners-enabled=false",
        "oms.inventory.expiry-scheduler-enabled=false",
        "oms.outbox.relay-enabled=false"})
class InventoryPostgresIT extends AbstractPostgresIT implements PostgresScenarios {

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
