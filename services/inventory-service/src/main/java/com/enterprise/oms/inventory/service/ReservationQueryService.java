package com.enterprise.oms.inventory.service;

import com.enterprise.oms.inventory.domain.Product;
import com.enterprise.oms.inventory.domain.Reservation;
import com.enterprise.oms.inventory.domain.ReservationItem;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.repo.ProductRepository;
import com.enterprise.oms.inventory.repo.ReservationItemRepository;
import com.enterprise.oms.inventory.repo.ReservationRepository;
import com.enterprise.oms.inventory.web.Dtos.ReservationItemResponse;
import com.enterprise.oms.inventory.web.Dtos.ReservationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read side of reservations: one page query + one items query + one products query (no N+1). */
@Service
public class ReservationQueryService {

    private final ReservationRepository reservations;
    private final ReservationItemRepository items;
    private final ProductRepository products;

    public ReservationQueryService(ReservationRepository reservations, ReservationItemRepository items,
                                   ProductRepository products) {
        this.reservations = reservations;
        this.items = items;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public Page<ReservationResponse> list(UUID orderId, ReservationStatus status, Pageable pageable) {
        Specification<Reservation> spec = Specification.where(null);
        if (orderId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("orderId"), orderId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        Page<Reservation> page = reservations.findAll(spec, pageable);
        List<UUID> reservationIds = page.getContent().stream().map(Reservation::getId).toList();
        Map<UUID, List<ReservationItem>> itemsByReservation = reservationIds.isEmpty() ? Map.of()
                : items.findByReservationIdIn(reservationIds).stream()
                        .collect(Collectors.groupingBy(ReservationItem::getReservationId));
        List<UUID> productIds = itemsByReservation.values().stream().flatMap(List::stream)
                .map(ReservationItem::getProductId).distinct().toList();
        Map<UUID, String> skus = productIds.isEmpty() ? Map.of()
                : products.findAllById(productIds).stream().collect(Collectors.toMap(Product::getId, Product::getSku));
        List<ReservationResponse> content = page.getContent().stream().map(r -> new ReservationResponse(
                r.getId(), r.getOrderId(), r.getStatus().name(),
                itemsByReservation.getOrDefault(r.getId(), List.of()).stream()
                        .sorted(Comparator.comparing(ReservationItem::getProductId))
                        .map(i -> new ReservationItemResponse(i.getProductId(), skus.get(i.getProductId()),
                                i.getQuantity())).toList(),
                r.getExpiresAt(), r.getCreatedAt(), r.getUpdatedAt(), r.getReleaseReason(), r.getFailureReason(),
                r.getShippedAt())).toList();
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }
}
