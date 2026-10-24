package com.enterprise.oms.common.events;

import java.util.List;

/** Kafka topic names (contract section 7). Key = orderId, 3 partitions, DLT suffix {@code .DLT}. */
public final class Topics {

    public static final String ORDER_EVENTS = "oms.order.events.v1";
    public static final String INVENTORY_COMMANDS = "oms.inventory.commands.v1";
    public static final String INVENTORY_EVENTS = "oms.inventory.events.v1";
    public static final String FULFILLMENT_COMMANDS = "oms.fulfillment.commands.v1";
    public static final String FULFILLMENT_EVENTS = "oms.fulfillment.events.v1";

    public static final String DLT_SUFFIX = ".DLT";
    public static final int PARTITIONS = 3;

    /** All business topics. */
    public static final List<String> ALL = List.of(
            ORDER_EVENTS, INVENTORY_COMMANDS, INVENTORY_EVENTS, FULFILLMENT_COMMANDS, FULFILLMENT_EVENTS);

    /** All business topics followed by their dead-letter topics. */
    public static final List<String> ALL_WITH_DLT = ALL.stream()
            .flatMap(t -> java.util.stream.Stream.of(t, dlt(t)))
            .toList();

    private Topics() {
    }

    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }
}
