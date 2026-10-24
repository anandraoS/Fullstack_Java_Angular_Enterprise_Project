package com.enterprise.oms.inventory.reservation;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.common.events.EventPayloads.Shortage;
import com.enterprise.oms.inventory.repo.StockRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads current availability for shortage reports (projection query: always hits the database, never a cache). */
@Component
public class ShortageCalculator {

    private final StockRepository stock;

    public ShortageCalculator(StockRepository stock) {
        this.stock = stock;
    }

    /** One entry per line whose requested quantity exceeds the available stock (or whose stock row is missing). */
    public List<Shortage> shortages(List<QuantityItem> lines) {
        Map<UUID, Integer> available = new HashMap<>();
        stock.snapshots(lines.stream().map(QuantityItem::productId).toList())
                .forEach(s -> available.put(s.getProductId(), s.getOnHand() - s.getReserved()));
        return lines.stream()
                .filter(l -> available.getOrDefault(l.productId(), 0) < l.quantity())
                .map(l -> new Shortage(l.productId(), l.quantity(), Math.max(0, available.getOrDefault(l.productId(), 0))))
                .toList();
    }
}
