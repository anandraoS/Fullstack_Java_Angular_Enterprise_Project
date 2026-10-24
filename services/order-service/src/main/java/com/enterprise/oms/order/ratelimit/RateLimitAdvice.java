package com.enterprise.oms.order.ratelimit;

import com.enterprise.oms.common.error.ErrorCode;
import com.enterprise.oms.common.error.ProblemFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Adds the {@code Retry-After} header to the standard RATE_LIMITED problem (the shared handler cannot). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitAdvice {

    @ExceptionHandler(OrderRateLimitedException.class)
    public ResponseEntity<ProblemDetail> handle(OrderRateLimitedException ex, HttpServletRequest request) {
        ProblemDetail pd = ProblemFactory.create(ErrorCode.RATE_LIMITED, ex.getMessage(), request.getRequestURI(), null);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
