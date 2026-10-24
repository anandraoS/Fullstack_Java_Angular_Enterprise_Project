package com.enterprise.oms.inventory;

import com.enterprise.oms.inventory.metrics.InventoryMetrics;
import com.enterprise.oms.inventory.reservation.ReservationStrategies;
import com.enterprise.oms.inventory.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

/** Opt-in benchmark on H2: {@code mvn -pl inventory-service test -Dtest=StrategyBenchmarkTest -Doms.bench=true}. */
@EnabledIfSystemProperty(named = "oms.bench", matches = "true")
class StrategyBenchmarkTest extends AbstractInventoryTest {

    @Autowired ReservationService reservations;
    @Autowired ReservationStrategies strategies;
    @Autowired InventoryMetrics metrics;

    @Test
    void compareStrategiesUnderContention() throws Exception {
        new BenchmarkRunner(jdbc, reservations, strategies, metrics).run("H2 2.x PostgreSQL mode (in-memory)");
    }
}
