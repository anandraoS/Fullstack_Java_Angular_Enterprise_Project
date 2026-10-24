package com.enterprise.oms.gateway.problem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeoutException;

/** Replaces Boot's default JSON error body with contract problem+json; never leaks exception details. */
@Component
@Order(-2)
public class ProblemErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorHandler.class);
    private final Problems problems;

    public ProblemErrorHandler(Problems problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        HttpStatusCode status = statusOf(ex);
        int code = status.value();
        if (code == 404) {
            return problems.write(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND", "Not Found", "No such resource.");
        } else if (code == 405) {
            return problems.write(exchange, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method Not Allowed",
                    "Method not allowed for this resource.");
        } else if (code == 406) {
            return problems.write(exchange, HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "Not Acceptable",
                    "Requested representation is not available.");
        } else if (code == 413) {
            return problems.write(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Payload Too Large",
                    "Request body is too large.");
        } else if (code == 415) {
            return problems.write(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "Unsupported Media Type", "Content type is not supported.");
        } else if (code == 502 || code == 503 || code == 504 || isUpstreamFailure(ex)) {
            log.warn("Upstream failure: {}", ex.getClass().getSimpleName());
            return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", "Service Unavailable",
                    "The upstream service is temporarily unavailable. Retry later.");
        } else if (code >= 400 && code < 500) {
            return problems.write(exchange, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Bad Request", "The request is invalid.");
        }
        log.error("Unhandled gateway error: {}", ex.getClass().getName());
        return problems.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal Server Error",
                "An unexpected error occurred.");
    }

    private static HttpStatusCode statusOf(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return rse.getStatusCode();
        }
        if (ex instanceof ServerWebInputException) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static boolean isUpstreamFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof TimeoutException || t instanceof ClosedChannelException
                    || t.getClass().getName().startsWith("io.netty.handler.timeout")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
