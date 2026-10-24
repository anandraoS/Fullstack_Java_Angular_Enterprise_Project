package com.enterprise.oms.inventory.service;

import com.enterprise.oms.inventory.domain.Product;
import com.enterprise.oms.inventory.domain.Stock;
import com.enterprise.oms.inventory.repo.ProductRepository;
import com.enterprise.oms.inventory.repo.StockRepository;
import com.enterprise.oms.inventory.web.Dtos.ValidateLine;
import com.enterprise.oms.inventory.web.Dtos.ValidateRequest;
import com.enterprise.oms.inventory.web.Dtos.ValidateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Backs {@code POST /api/v1/internal/products/validate} (order-service saga step VALIDATE). Reads the database
 * directly, never the product cache: the answer feeds an order decision. It is advisory - stock can still change
 * before the reservation; the reservation (atomic conditional UPDATE) is the authoritative check.
 */
@Service
public class ValidationService {

    private final ProductRepository products;
    private final StockRepository stock;

    public ValidationService(ProductRepository products, StockRepository stock) {
        this.products = products;
        this.stock = stock;
    }

    @Transactional(readOnly = true)
    public ValidateResponse validate(ValidateRequest request) {
        List<UUID> ids = request.items().stream().map(ValidateRequest.Line::productId).distinct().toList();
        Map<UUID, Product> catalog = products.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, Stock> stocks = stock.findAllById(ids).stream()
                .collect(Collectors.toMap(Stock::getProductId, Function.identity()));
        // requested quantity per product (duplicate lines are summed so two lines cannot each "fit" separately)
        Map<UUID, Integer> requested = request.items().stream()
                .collect(Collectors.toMap(ValidateRequest.Line::productId, ValidateRequest.Line::quantity, Integer::sum));

        List<ValidateLine> lines = new ArrayList<>();
        boolean valid = true;
        for (ValidateRequest.Line line : request.items()) {
            Product p = catalog.get(line.productId());
            if (p == null) {
                lines.add(new ValidateLine(line.productId(), null, null, null, 0, false, false, "NOT_FOUND"));
                valid = false;
                continue;
            }
            Stock s = stocks.get(line.productId());
            int available = s == null ? 0 : Math.max(0, s.available());
            String reason = null;
            if (!p.isActive()) {
                reason = "INACTIVE";
            } else if (requested.get(line.productId()) > available) {
                reason = "INSUFFICIENT_STOCK";
            }
            boolean ok = reason == null;
            valid &= ok;
            lines.add(new ValidateLine(p.getId(), p.getSku(), p.getName(), p.getPrice(), available, p.isActive(), ok,
                    reason));
        }
        return new ValidateResponse(valid, lines);
    }
}
