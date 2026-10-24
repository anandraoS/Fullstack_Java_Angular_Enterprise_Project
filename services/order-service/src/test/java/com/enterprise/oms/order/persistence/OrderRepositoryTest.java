package com.enterprise.oms.order.persistence;

import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.order.api.dto.OrderView;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.IdempotencyKeyRecord;
import com.enterprise.oms.order.entity.SagaInstance;
import com.enterprise.oms.order.entity.ShippingAddress;
import com.enterprise.oms.order.repository.IdempotencyKeyRepository;
import com.enterprise.oms.order.service.OrderQueryService;
import com.enterprise.oms.order.service.OrderSpecifications;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schema, constraints, specifications and the "no N+1" guarantee, on H2 in PostgreSQL mode with the real migrations. */
class OrderRepositoryTest extends AbstractOrderSpringTest {

    @Autowired OrderQueryService queryService;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdempotencyKeyRepository keys;

    private CustomerOrder newOrder(Customer c, Instant createdAt, int lines, String city) {
        CustomerOrder o = new CustomerOrder(UUID.randomUUID(), c.getId(), c.getEmail(),
                new ShippingAddress("1 Repo Street", city, "12345", "DE"), "corr-" + UUID.randomUUID(), createdAt);
        for (int i = 0; i < lines; i++) {
            o.addItem(UUID.randomUUID(), i + 1);
        }
        return o;
    }

    private static CurrentUser staff() {
        return new CurrentUser("operator-x", Set.of("OPERATOR"), null);
    }

    @Test
    void migrationsCreatedTheTablesAndTheDocumentedIndexes() {
        List<String> tables = jdbc.queryForList("select lower(table_name) from information_schema.tables where lower(table_schema) = 'public'", String.class);
        assertThat(tables).contains("customers", "orders", "order_items", "order_status_history", "saga_instances",
                "saga_step_log", "idempotency_keys", "outbox_events", "processed_events", "audit_events",
                "flyway_schema_history");
        List<String> indexes = jdbc.queryForList("select lower(index_name) from information_schema.indexes where lower(table_schema) = 'public'", String.class);
        assertThat(indexes).contains("ix_orders_customer_created", "ix_orders_customer_status_created",
                "ix_orders_status_created", "ix_orders_created", "ix_order_items_order", "ix_history_order",
                "ix_saga_state_updated", "ix_saga_reservation", "ix_step_log_order", "ix_idempotency_created");
        List<String> versions = jdbc.queryForList("select version from flyway_schema_history order by installed_rank", String.class);
        assertThat(versions).containsSubsequence("1", "10", "11", "12").doesNotContain("1000");   // seed only in dev
    }

    @Test
    void uniqueConstraintsHoldAtTheDatabaseLevel() {
        keys.saveAndFlush(new IdempotencyKeyRecord("s1", "dup-key-0001", "h".repeat(64), Instant.now()));
        assertThatThrownBy(() -> keys.saveAndFlush(new IdempotencyKeyRecord("s1", "dup-key-0001", "x".repeat(64), Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        keys.saveAndFlush(new IdempotencyKeyRecord("s2", "dup-key-0001", "h".repeat(64), Instant.now()));   // other scope: fine

        Customer c = newCustomer();
        CustomerOrder o = orders.saveAndFlush(newOrder(c, Instant.now(), 1, "Bonn"));
        sagas.saveAndFlush(new SagaInstance(o.getId(), Instant.now()));
        assertThatThrownBy(() -> sagas.saveAndFlush(new SagaInstance(o.getId(), Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> customers.saveAndFlush(new Customer(UUID.randomUUID(), "Dup", c.getEmail(),
                com.enterprise.oms.order.domain.CustomerTier.STANDARD, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void foreignKeysRejectOrphans() {
        assertThatThrownBy(() -> orders.saveAndFlush(newOrder(new Customer(UUID.randomUUID(), "ghost", "g@example.test",
                com.enterprise.oms.order.domain.CustomerTier.STANDARD, Instant.now()), Instant.now(), 1, "X")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void searchingAPageOfOrdersWithItemsTakesAConstantNumberOfStatements() {
        Customer c = newCustomer();
        Instant base = Instant.now().minusSeconds(1000);
        for (int i = 0; i < 25; i++) {
            orders.save(newOrder(c, base.plusSeconds(i), 3, "Hamburg"));
        }
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        PageResponse<OrderView> page = queryService.search(new OrderQueryService.OrderSearch(null, c.getId(), null, null, null),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")), staff());

        assertThat(page.content()).hasSize(20);
        assertThat(page.totalElements()).isEqualTo(25);
        assertThat(page.content()).allSatisfy(o -> assertThat(o.items()).hasSize(3));
        // 1 page select + 1 count + 1 fetch-join of the items of those 20 ids = 3, NOT 1 + 20 (N+1)
        assertThat(stats.getPrepareStatementCount()).as("statements for a page of 20 orders x 3 items").isLessThanOrEqualTo(3);
    }

    @Test
    void searchOrderIsStableAndFollowsTheRequestedSort() {
        Customer c = newCustomer();
        Instant t = Instant.now().minusSeconds(500);
        CustomerOrder a = orders.save(newOrder(c, t, 1, "A-city"));
        CustomerOrder b = orders.save(newOrder(c, t.plusSeconds(1), 1, "B-city"));
        CustomerOrder d = orders.save(newOrder(c, t.plusSeconds(2), 1, "C-city"));

        var desc = queryService.search(new OrderQueryService.OrderSearch(null, c.getId(), null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")), staff());
        var asc = queryService.search(new OrderQueryService.OrderSearch(null, c.getId(), null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "createdAt")), staff());

        assertThat(desc.content()).extracting(OrderView::id).containsExactly(d.getId(), b.getId(), a.getId());
        assertThat(asc.content()).extracting(OrderView::id).containsExactly(a.getId(), b.getId(), d.getId());
    }

    @Test
    void specificationsCombineAndEscapeLikeWildcards() {
        Customer c = newCustomer();
        Instant t = Instant.now().minusSeconds(100);
        orders.save(newOrder(c, t, 1, "Cologne"));
        orders.save(newOrder(c, t.plusSeconds(10), 1, "100%_city"));
        long both = orders.count(Specification.where(OrderSpecifications.customer(c.getId()))
                .and(OrderSpecifications.createdFrom(t.minusSeconds(1))).and(OrderSpecifications.createdTo(t.plusSeconds(20))));
        long onlyFirst = orders.count(Specification.where(OrderSpecifications.customer(c.getId()))
                .and(OrderSpecifications.createdTo(t.plusSeconds(5))));
        long cityPrefix = orders.count(Specification.where(OrderSpecifications.customer(c.getId())).and(OrderSpecifications.text("colo")));
        long percentLiteral = orders.count(Specification.where(OrderSpecifications.customer(c.getId())).and(OrderSpecifications.text("100%")));
        long underscoreNotWildcard = orders.count(Specification.where(OrderSpecifications.customer(c.getId())).and(OrderSpecifications.text("1_0")));
        long status = orders.count(Specification.where(OrderSpecifications.customer(c.getId())).and(OrderSpecifications.status(OrderStatus.FAILED)));

        assertThat(both).isEqualTo(2);
        assertThat(onlyFirst).isEqualTo(1);
        assertThat(cityPrefix).isEqualTo(1);
        assertThat(percentLiteral).isEqualTo(1);
        assertThat(underscoreNotWildcard).isZero();
        assertThat(status).isZero();
    }

    @Test
    void optimisticVersionIncrementsOnEveryUpdate() {
        Customer c = newCustomer();
        CustomerOrder saved = orders.saveAndFlush(newOrder(c, Instant.now(), 1, "Ulm"));
        assertThat(saved.getVersion()).isZero();
        tx.executeWithoutResult(s -> orders.findById(saved.getId()).orElseThrow()
                .changeStatus(OrderStatus.VALIDATED, "r", Instant.now()));
        assertThat(orders.findById(saved.getId()).orElseThrow().getVersion()).isEqualTo(1L);
    }

    @Test
    void moneyKeepsScaleTwoThroughThePersistenceLayer() {
        Customer c = newCustomer();
        CustomerOrder o = newOrder(c, Instant.now(), 1, "Kiel");
        o.applyPrices(java.util.Map.of(o.getItems().get(0).getProductId(),
                new CustomerOrder.PricedLine("SKU-X", "X", new java.math.BigDecimal("0.1"))));
        orders.saveAndFlush(o);

        CustomerOrder loaded = order(o.getId());
        assertThat(loaded.getItems().get(0).getUnitPrice().toPlainString()).isEqualTo("0.10");
        assertThat(loaded.getTotalAmount().toPlainString()).isEqualTo("0.10");
    }
}
