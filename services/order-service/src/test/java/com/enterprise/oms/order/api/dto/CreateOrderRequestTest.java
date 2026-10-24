package com.enterprise.oms.order.api.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Bean Validation rules and the canonical form used for Idempotency-Key fingerprints. */
class CreateOrderRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private final UUID customer = UUID.randomUUID();
    private final UUID p1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID p2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final AddressDto address = new AddressDto("1 Main", "Berlin", "10115", "DE");

    private List<String> violations(CreateOrderRequest r) {
        return validator.validate(r).stream().map(v -> v.getPropertyPath().toString()).sorted().collect(Collectors.toList());
    }

    @Test
    void aValidRequestHasNoViolations() {
        assertThat(violations(new CreateOrderRequest(customer, List.of(new OrderItemRequest(p1, 1)), address))).isEmpty();
    }

    @Test
    void itemsMustHaveOneToFiftyLines() {
        assertThat(violations(new CreateOrderRequest(customer, List.of(), address))).containsExactly("items");
        List<OrderItemRequest> fiftyOne = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            fiftyOne.add(new OrderItemRequest(UUID.randomUUID(), 1));
        }
        assertThat(violations(new CreateOrderRequest(customer, fiftyOne, address))).containsExactly("items");
        assertThat(violations(new CreateOrderRequest(customer, fiftyOne.subList(0, 50), address))).isEmpty();
    }

    @Test
    void quantityMustBeBetween1And1000() {
        for (int bad : new int[]{0, -1, 1001}) {
            assertThat(violations(new CreateOrderRequest(customer, List.of(new OrderItemRequest(p1, bad)), address)))
                    .as("quantity %d", bad).hasSize(1);
        }
        for (int ok : new int[]{1, 1000}) {
            assertThat(violations(new CreateOrderRequest(customer, List.of(new OrderItemRequest(p1, ok)), address))).isEmpty();
        }
    }

    @Test
    void nullsAndBlanksAreViolations() {
        assertThat(violations(new CreateOrderRequest(null, null, null))).containsExactly("customerId", "items", "shippingAddress");
        assertThat(violations(new CreateOrderRequest(customer, List.of(new OrderItemRequest(null, null)),
                new AddressDto(" ", "", null, "D")))).hasSize(6);   // productId, quantity, line1, city, postalCode, country
    }

    @Test
    void canonicalFormIgnoresLineOrderButNotValues() {
        CreateOrderRequest a = new CreateOrderRequest(customer, List.of(new OrderItemRequest(p1, 1), new OrderItemRequest(p2, 2)), address);
        CreateOrderRequest reordered = new CreateOrderRequest(customer, List.of(new OrderItemRequest(p2, 2), new OrderItemRequest(p1, 1)), address);
        CreateOrderRequest otherQty = new CreateOrderRequest(customer, List.of(new OrderItemRequest(p1, 1), new OrderItemRequest(p2, 3)), address);
        CreateOrderRequest otherCity = new CreateOrderRequest(customer, a.items(), new AddressDto("1 Main", "Paris", "10115", "DE"));
        CreateOrderRequest padded = new CreateOrderRequest(customer, a.items(), new AddressDto(" 1 Main ", " Berlin", "10115 ", "DE"));

        assertThat(reordered.canonical()).isEqualTo(a.canonical());
        assertThat(padded.canonical()).isEqualTo(a.canonical());
        assertThat(otherQty.canonical()).isNotEqualTo(a.canonical());
        assertThat(otherCity.canonical()).isNotEqualTo(a.canonical());
    }
}
