package com.enterprise.oms.localstack;

import java.util.List;

/**
 * Topics pre-created in the embedded broker (contract section 7): every business topic plus its {@code .DLT}.
 * Kept as literals so this module has no runtime dependency on platform-common; a unit test asserts it equals
 * {@code Topics.ALL_WITH_DLT}.
 */
public final class TopicCatalog {

    public static final int PARTITIONS = 3;

    public static final List<String> TOPICS = List.of(
            "oms.order.events.v1", "oms.order.events.v1.DLT",
            "oms.inventory.commands.v1", "oms.inventory.commands.v1.DLT",
            "oms.inventory.events.v1", "oms.inventory.events.v1.DLT",
            "oms.fulfillment.commands.v1", "oms.fulfillment.commands.v1.DLT",
            "oms.fulfillment.events.v1", "oms.fulfillment.events.v1.DLT");

    private TopicCatalog() {
    }
}
