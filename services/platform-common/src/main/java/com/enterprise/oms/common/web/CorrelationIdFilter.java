package com.enterprise.oms.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Accepts {@code X-Correlation-Id} (validated: 1-64 chars of A-Za-z0-9._-, otherwise a new UUID is generated),
 * binds it to the MDC key {@code correlationId} and echoes it on the response. Runs first in the chain so every
 * later log line (including security failures) carries it.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(CorrelationIds.HEADER);
        if (!CorrelationIds.isValid(id)) {
            id = CorrelationIds.newId();
        }
        MDC.put(CorrelationIds.MDC_KEY, id);
        response.setHeader(CorrelationIds.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}
