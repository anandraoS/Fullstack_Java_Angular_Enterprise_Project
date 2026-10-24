package com.enterprise.oms.gateway.filter;

import com.enterprise.oms.gateway.config.GatewayProperties;
import com.enterprise.oms.gateway.problem.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Caps request bodies at {@code oms.gateway.max-body-bytes} (64 KB): a declared Content-Length above the limit is rejected
 * without reading the body; chunked bodies are buffered up to the limit (so at most 64 KB per request is held) and rejected
 * with 413 {@code PAYLOAD_TOO_LARGE} when they exceed it.
 */
@Component
public class RequestSizeLimitWebFilter implements WebFilter, Ordered {

    private final int maxBytes;
    private final Problems problems;

    public RequestSizeLimitWebFilter(GatewayProperties props, Problems problems) {
        this.maxBytes = props.getMaxBodyBytes();
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long declared = request.getHeaders().getContentLength();
        if (declared > maxBytes) {
            return tooLarge(exchange);
        }
        String te = request.getHeaders().getFirst("Transfer-Encoding");
        boolean chunked = te != null && te.toLowerCase().contains("chunked");
        if (declared >= 0 || !chunked) {
            return chain.filter(exchange);
        }
        return DataBufferUtils.join(request.getBody(), maxBytes)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(body -> {
                    ServerHttpRequest decorated = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return body.<Flux<DataBuffer>>map(Flux::just).orElseGet(Flux::empty);
                        }
                    };
                    return chain.filter(exchange.mutate().request(decorated).build());
                })
                .onErrorResume(DataBufferLimitException.class, e -> tooLarge(exchange));
    }

    private Mono<Void> tooLarge(ServerWebExchange exchange) {
        return problems.write(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Payload Too Large",
                "Request body exceeds the " + maxBytes + " byte limit.");
    }

    @Override
    public int getOrder() {
        return EdgeFilters.Order.REQUEST_SIZE;
    }
}
