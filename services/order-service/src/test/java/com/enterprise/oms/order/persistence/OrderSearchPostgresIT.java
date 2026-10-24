package com.enterprise.oms.order.persistence;

import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.order.api.dto.OrderView;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.service.OrderQueryService;
import com.enterprise.oms.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real migrations on PostgreSQL 16 (Testcontainers) and checks, with EXPLAIN, that the search queries the API
 * generates use the indexes designed in V12. Skipped automatically when Docker is not available
 * ({@code @Testcontainers(disabledWithoutDocker = true)} in AbstractPostgresIT): it was NOT executed on the machine
 * this service was built on (no Docker there), see README "Verification status".
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"oms.saga.listeners-enabled=false", "oms.outbox.relay-enabled=false"})
class OrderSearchPostgresIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired OrderQueryService queries;

    private static boolean loaded;

    /** 50 customers x 200 orders = 10 000 orders, spread over 10 days, mixed statuses; then ANALYZE. */
    private void loadData() {
        if (loaded) {
            return;
        }
        jdbc.execute("insert into customers (id, name, email, tier, created_at) "
                + "select gen_random_uuid(), 'Customer ' || g, 'pg-' || g || '@example.test', 'STANDARD', now() from generate_series(1, 50) g");
        jdbc.execute("insert into orders (id, customer_id, customer_email, status, total_amount, currency, ship_line1, ship_city, "
                + "ship_postal_code, ship_country, created_at, updated_at, version) "
                + "select gen_random_uuid(), c.id, c.email, "
                + "(array['CREATED','VALIDATED','CONFIRMED','FULFILLED','CANCELLED','FAILED','REJECTED'])[1 + (g % 7)], 10.00, 'USD', "
                + "'1 Main', 'City' || (g % 20), '12345', 'DE', now() - (g || ' minutes')::interval * 72, now(), 0 "
                + "from (select id, email, row_number() over () rn from customers) c cross join generate_series(1, 200) g");
        jdbc.execute("analyze");
        loaded = true;
    }

    private String explain(String sql, boolean seqScanOff, Object... args) {
        return jdbc.execute((java.sql.Connection con) -> {
            try (Statement st = con.createStatement()) {
                st.execute("set enable_seqscan = " + (seqScanOff ? "off" : "on"));
            }
            try (var ps = con.prepareStatement("explain " + sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                StringBuilder plan = new StringBuilder();
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
                try (Statement st = con.createStatement()) {
                    st.execute("reset enable_seqscan");
                }
                return plan.toString();
            }
        });
    }

    @Test
    void migrationsRanOnPostgres() {
        List<String> versions = jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(versions).containsSubsequence("1", "10", "11", "12");
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname like 'ix_orders_%'", Integer.class))
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void customerSearchUsesTheCustomerCreatedIndex() {
        loadData();
        String customerId = jdbc.queryForObject("select id::text from customers where email = 'pg-7@example.test'", String.class);

        String plan = explain("select * from orders where customer_id = ?::uuid order by created_at desc limit 20", false, customerId);

        assertThat(plan).contains("ix_orders_customer_");
        assertThat(plan).doesNotContain("Seq Scan on orders");
    }

    @Test
    void customerPlusStatusSearchUsesTheCompositeIndex() {
        loadData();
        String customerId = jdbc.queryForObject("select id::text from customers where email = 'pg-9@example.test'", String.class);

        String plan = explain("select * from orders where customer_id = ?::uuid and status = 'FAILED' order by created_at desc limit 20",
                false, customerId);

        assertThat(plan).containsAnyOf("ix_orders_customer_status_created", "ix_orders_customer_created");
    }

    @Test
    void statusAndDateRangeSearchUsesAStatusOrCreatedIndex() {
        loadData();

        String plan = explain("select * from orders where status = 'FAILED' and created_at >= now() - interval '1 hour' "
                + "order by created_at desc limit 20", true);

        assertThat(plan).containsAnyOf("ix_orders_status_created", "ix_orders_created");
    }

    @Test
    void theDefaultListingIsServedByTheCreatedAtIndexInBackwardOrder() {
        loadData();

        String plan = explain("select * from orders order by created_at desc, id limit 20", true);

        assertThat(plan).contains("ix_orders_created");
    }

    @Test
    void sagaAndIdempotencyLookupsUseTheirUniqueIndexes() {
        String sagaPlan = explain("select * from saga_instances where order_id = '00000000-0000-0000-0000-000000000001'::uuid", true);
        String keyPlan = explain("select * from idempotency_keys where scope = 'x' and idem_key = 'y'", true);

        assertThat(sagaPlan).contains("uq_saga_order");
        assertThat(keyPlan).contains("uq_idempotency_scope_key");
    }

    @Test
    void theApplicationQueryReturnsCorrectPagesOnPostgres() {
        loadData();
        String customerId = jdbc.queryForObject("select id::text from customers where email = 'pg-3@example.test'", String.class);
        CurrentUser staff = new CurrentUser("operator-pg", Set.of("OPERATOR"), null);

        PageResponse<OrderView> page = queries.search(new OrderQueryService.OrderSearch(OrderStatus.FAILED,
                java.util.UUID.fromString(customerId), null, null, null), PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")), staff);

        long expected = jdbc.queryForObject("select count(*) from orders where customer_id = ?::uuid and status = 'FAILED'", Long.class, customerId);
        assertThat(page.totalElements()).isEqualTo(expected);
        assertThat(page.content()).allSatisfy(o -> assertThat(o.status()).isEqualTo(OrderStatus.FAILED));
        assertThat(page.content()).extracting(OrderView::createdAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }
}
