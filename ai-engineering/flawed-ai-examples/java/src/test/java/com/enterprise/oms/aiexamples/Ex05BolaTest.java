package com.enterprise.oms.aiexamples;

import com.enterprise.oms.aiexamples.Ex05Bola.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class Ex05BolaTest {

    private static final UUID CUSTOMER_1 = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER_2 = UUID.fromString("c0000000-0000-0000-0000-000000000002");
    private final CurrentUser customer1 = new CurrentUser("customer1", Set.of("CUSTOMER"), CUSTOMER_1);
    private final CurrentUser customer2 = new CurrentUser("customer2", Set.of("CUSTOMER"), CUSTOMER_2);
    private final CurrentUser operator = new CurrentUser("operator1", Set.of("OPERATOR"), null);

    @Test
    void flawedLetsCustomer1CreateAnOrderForCustomer2() {
        FlawedOrderController c = new FlawedOrderController(new OrderStore());
        Order o = c.create(new CreateOrderRequest(CUSTOMER_2, List.of("p1")), customer1);
        assertEquals(CUSTOMER_2, o.customerId(), "BUG MANIFESTS: body customerId was trusted");
    }

    @Test
    void flawedLetsCustomer2ReadCustomer1sOrder() {
        OrderStore store = new OrderStore();
        Order mine = store.save(new Order(UUID.randomUUID(), CUSTOMER_1));
        Order leaked = new FlawedOrderController(store).get(mine.id(), customer2);
        assertEquals(mine, leaked, "BUG MANIFESTS: cross-customer read succeeded");
    }

    @Test
    void fixedRejectsCreateForAnotherCustomer() {
        FixedOrderController c = new FixedOrderController(new OrderStore());
        assertThrows(ForbiddenException.class,
                () -> c.create(new CreateOrderRequest(CUSTOMER_2, List.of("p1")), customer1));
        assertEquals(CUSTOMER_1, c.create(new CreateOrderRequest(CUSTOMER_1, List.of("p1")), customer1).customerId());
    }

    @Test
    void fixedAnswersNotFoundForAnotherCustomersOrderAndForMissingOrdersAlike() {
        OrderStore store = new OrderStore();
        Order mine = store.save(new Order(UUID.randomUUID(), CUSTOMER_1));
        FixedOrderController c = new FixedOrderController(store);

        assertThrows(NotFoundException.class, () -> c.get(mine.id(), customer2));
        assertThrows(NotFoundException.class, () -> c.get(UUID.randomUUID(), customer2));
        assertEquals(mine, c.get(mine.id(), customer1));
        assertEquals(mine, c.get(mine.id(), operator), "staff may read any order");
    }
}
