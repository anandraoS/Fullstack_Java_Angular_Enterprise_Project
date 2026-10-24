package com.enterprise.oms.common.testapp;

import com.enterprise.oms.common.error.ConflictException;
import com.enterprise.oms.common.error.ForbiddenException;
import com.enterprise.oms.common.error.InsufficientStockException;
import com.enterprise.oms.common.error.InvalidStateTransitionException;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.common.web.IdempotencyKeys;
import com.enterprise.oms.common.web.PageRequestFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class TestController {

    record Body(@NotBlank String name, @Min(1) int qty) {
    }

    private final PageRequestFactory pages;

    TestController(PageRequestFactory pages) {
        this.pages = pages;
    }

    @GetMapping("/test/ok")
    Map<String, String> ok() {
        return Map.of("status", "ok");
    }

    @GetMapping("/test/me")
    Map<String, Object> me() {
        CurrentUser u = CurrentUser.require();
        return Map.of("id", u.id(), "roles", u.roles().stream().sorted().toList(),
                "customerId", String.valueOf(u.customerId()));
    }

    @GetMapping("/test/admin")
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, String> admin() {
        return Map.of("status", "admin");
    }

    @GetMapping("/internal/ping")
    Map<String, String> internal() {
        return Map.of("status", "internal");
    }

    @GetMapping("/test/not-found")
    void notFound() {
        throw new NotFoundException("Order", UUID.fromString("00000000-0000-0000-0000-000000000042"));
    }

    @GetMapping("/test/conflict")
    void conflict() {
        throw new ConflictException("duplicate sku");
    }

    @GetMapping("/test/forbidden")
    void forbidden() {
        throw new ForbiddenException("not yours");
    }

    @GetMapping("/test/state")
    void state() {
        throw new InvalidStateTransitionException("Order", "FULFILLED", "CANCELLED");
    }

    @GetMapping("/test/stock")
    void stock() {
        throw new InsufficientStockException(List.of(
                new InsufficientStockException.Shortage(UUID.fromString("00000000-0000-0000-0000-000000000001"), 5, 2)));
    }

    @GetMapping("/test/boom")
    void boom() {
        throw new IllegalStateException("select * from secret_table where password = 'x'");
    }

    @PostMapping(value = "/test/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> validate(@Valid @RequestBody Body body) {
        return Map.of("name", body.name());
    }

    @PostMapping(value = "/test/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Integer> echo(@RequestBody String body) {
        return Map.of("length", body.length());
    }

    @GetMapping("/test/page")
    Map<String, Object> page(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                             @RequestParam(required = false) List<String> sort) {
        Pageable p = pages.create(page, size, sort, Set.of("name", "createdAt"), Sort.by("name"));
        return Map.of("page", p.getPageNumber(), "size", p.getPageSize(), "sort", p.getSort().toString());
    }

    @GetMapping("/test/idem")
    Map<String, String> idem(@RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return Map.of("key", IdempotencyKeys.require(key));
    }
}
