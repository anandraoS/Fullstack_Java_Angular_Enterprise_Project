package com.enterprise.oms.common.web;

import com.enterprise.oms.common.error.FieldError;
import com.enterprise.oms.common.error.IdempotencyKeyRequiredException;
import com.enterprise.oms.common.error.RequestValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRequestFactoryTest {

    private final PageRequestFactory factory = new PageRequestFactory();
    private final Set<String> sortable = Set.of("createdAt", "status");
    private final Sort def = Sort.by(Sort.Direction.DESC, "createdAt");

    @Test
    void appliesDefaults() {
        Pageable p = factory.create(null, null, (List<String>) null, sortable, def);
        assertThat(p.getPageNumber()).isZero();
        assertThat(p.getPageSize()).isEqualTo(20);
        assertThat(p.getSort()).isEqualTo(def);
    }

    @Test
    void parsesWhitelistedSort() {
        Pageable p = factory.create(2, 100, List.of("status,desc", "createdAt"), sortable, def);
        assertThat(p.getPageNumber()).isEqualTo(2);
        assertThat(p.getPageSize()).isEqualTo(100);
        assertThat(p.getSort().getOrderFor("status").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(p.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void rejectsOversizeNegativePageAndUnknownSortFields() {
        assertThatThrownBy(() -> factory.create(0, 101, "status", sortable, def))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).errors()).extracting(FieldError::field)
                        .containsExactly("size"));
        assertThatThrownBy(() -> factory.create(-1, 0, "password,asc", sortable, def))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).errors()).extracting(FieldError::field)
                        .containsExactlyInAnyOrder("page", "size", "sort"));
        assertThatThrownBy(() -> factory.create(0, 10, "status,sideways", sortable, def))
                .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void idempotencyKeyValidation() {
        assertThat(IdempotencyKeys.require(" abc12345-_X ")).isEqualTo("abc12345-_X");
        assertThatThrownBy(() -> IdempotencyKeys.require(null)).isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> IdempotencyKeys.require("  ")).isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> IdempotencyKeys.require("short")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> IdempotencyKeys.require("bad key with spaces!!"))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> IdempotencyKeys.require("a".repeat(65))).isInstanceOf(RequestValidationException.class);
        assertThat(IdempotencyKeys.fingerprint("{}")).hasSize(64).isEqualTo(IdempotencyKeys.fingerprint("{}"))
                .isNotEqualTo(IdempotencyKeys.fingerprint("{ }"));
    }
}
