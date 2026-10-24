package com.enterprise.oms.inventory.reservation;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.common.events.EventPayloads.Shortage;
import com.enterprise.oms.inventory.domain.Stock;
import com.enterprise.oms.inventory.repo.StockRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code SELECT ... FOR UPDATE} (JPA {@code PESSIMISTIC_WRITE}) every line in ascending productId order, then check
 * and write on the locked entities. Because ALL rows are locked before any decision, a shortage needs no compensation
 * (nothing was written) and the full shortage list is computed from the locked state. Cost: every reservation of a hot
 * product queues behind the previous one for the whole transaction, and rows are locked even when the answer is "no".
 */
@Component
public class PessimisticLockReservationStrategy implements ReservationStrategy {

    public static final String NAME = "pessimistic";

    private final StockRepository stock;
    private final Clock clock;

    public PessimisticLockReservationStrategy(StockRepository stock, Clock clock) {
        this.stock = stock;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Result tryReserve(List<QuantityItem> lines) {
        Instant now = clock.instant();
        Map<UUID, Stock> locked = new LinkedHashMap<>();
        List<Shortage> short_ = new ArrayList<>();
        for (QuantityItem line : lines) {                       // ascending productId = global lock order
            Stock s = stock.findForUpdate(line.productId()).orElse(null);
            if (s == null) {
                short_.add(new Shortage(line.productId(), line.quantity(), 0));
                continue;
            }
            locked.put(line.productId(), s);
            if (s.available() < line.quantity()) {
                short_.add(new Shortage(line.productId(), line.quantity(), Math.max(0, s.available())));
            }
        }
        if (!short_.isEmpty()) {
            return Result.short_(short_);
        }
        for (QuantityItem line : lines) {
            Stock s = locked.get(line.productId());
            s.setReserved(s.getReserved() + line.quantity(), now);   // flushed by the caller's transaction (@Version bump)
        }
        return Result.ok();
    }
}
