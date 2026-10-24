package com.enterprise.oms.order.saga;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.outbox.OutboxService;
import com.enterprise.oms.common.web.CorrelationIds;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.OrderItem;
import com.enterprise.oms.order.config.SagaProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Builds every event and command order-service emits and appends it to the transactional outbox (MANDATORY
 * transaction: the row commits or rolls back together with the state change that caused it).
 * Topics come from {@code EventTypes.topicFor}; the key is the orderId so per-order ordering holds.
 */
@Component
public class SagaMessaging {

    public static final String PRODUCER = "order-service";

    private final OutboxService outbox;
    private final SagaProperties props;

    public SagaMessaging(OutboxService outbox, SagaProperties props) {
        this.outbox = outbox;
        this.props = props;
    }

    private <T> void send(String type, CustomerOrder o, UUID causation, T payload) {
        String correlation = CorrelationIds.isValid(o.getCorrelationId()) ? o.getCorrelationId()
                : CorrelationIds.newId();
        outbox.append(EventEnvelope.forOrder(type, o.getId(), PRODUCER, correlation, causation, payload));
    }

    // ---- events on oms.order.events.v1 --------------------------------------------------------------------------

    public void orderCreated(CustomerOrder o, UUID causation) {
        List<EventPayloads.PricedItem> items = o.getItems().stream()
                .map(i -> new EventPayloads.PricedItem(i.getProductId(), i.getSku(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        send(EventTypes.ORDER_CREATED, o, causation, new EventPayloads.OrderCreated(o.getId(), o.getCustomerId(),
                o.getCustomerEmail(), o.getTotalAmount(), o.getCurrency(), items, o.getShippingAddress().getCity()));
    }

    public void orderConfirmed(CustomerOrder o, UUID causation) {
        send(EventTypes.ORDER_CONFIRMED, o, causation, new EventPayloads.OrderConfirmed(o.getId(), o.getCustomerId(),
                o.getCustomerEmail(), o.getTotalAmount()));
    }

    public void orderCancelled(CustomerOrder o, String reason, UUID causation) {
        send(EventTypes.ORDER_CANCELLED, o, causation, new EventPayloads.OrderCancelled(o.getId(), o.getCustomerId(),
                o.getCustomerEmail(), reason));
    }

    public void orderRejected(CustomerOrder o, String reason, UUID causation) {
        send(EventTypes.ORDER_REJECTED, o, causation, new EventPayloads.OrderRejected(o.getId(), o.getCustomerId(),
                o.getCustomerEmail(), reason));
    }

    public void orderFulfilled(CustomerOrder o, String trackingNumber, UUID causation) {
        send(EventTypes.ORDER_FULFILLED, o, causation, new EventPayloads.OrderFulfilled(o.getId(), o.getCustomerId(),
                o.getCustomerEmail(), trackingNumber));
    }

    public void orderFailed(CustomerOrder o, String reason, UUID causation) {
        send(EventTypes.ORDER_FAILED, o, causation, new EventPayloads.OrderFailed(o.getId(), o.getCustomerId(),
                o.getCustomerEmail(), reason));
    }

    // ---- commands -----------------------------------------------------------------------------------------------

    /** oms.inventory.commands.v1; idempotent at inventory (unique order_id). */
    public void reserveInventory(CustomerOrder o, UUID causation) {
        List<EventPayloads.QuantityItem> items = o.getItems().stream()
                .map((OrderItem i) -> new EventPayloads.QuantityItem(i.getProductId(), i.getQuantity())).toList();
        send(EventTypes.RESERVE_INVENTORY, o, causation,
                new EventPayloads.ReserveInventory(o.getId(), items, props.getReservationTtlSeconds()));
    }

    /** oms.inventory.commands.v1; reason is one of {@code EventPayloads.ReleaseReasons}. Idempotent at inventory. */
    public void releaseInventory(CustomerOrder o, String reason, UUID causation) {
        send(EventTypes.RELEASE_INVENTORY, o, causation, new EventPayloads.ReleaseInventory(o.getId(), reason));
    }

    /** oms.fulfillment.commands.v1; idempotent at fulfillment. */
    public void startFulfillment(CustomerOrder o, UUID causation) {
        List<EventPayloads.SkuItem> items = o.getItems().stream()
                .map(i -> new EventPayloads.SkuItem(i.getProductId(), i.getSku(), i.getQuantity())).toList();
        var a = o.getShippingAddress();
        send(EventTypes.START_FULFILLMENT, o, causation, new EventPayloads.StartFulfillment(o.getId(),
                new EventPayloads.Address(a.getLine1(), a.getCity(), a.getPostalCode(), a.getCountry()), items));
    }
}
