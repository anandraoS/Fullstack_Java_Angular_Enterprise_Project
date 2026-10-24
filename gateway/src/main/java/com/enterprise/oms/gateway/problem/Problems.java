package com.enterprise.oms.gateway.problem;

import com.enterprise.oms.common.web.CorrelationIds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RFC 7807 problem+json writer shaped as in contract section 5 (no stack traces, no internals). */
@Component
public class Problems {

    public static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");
    /** Exchange attribute holding the correlation id (set by the correlation filter). */
    public static final String CORRELATION_ATTR = Problems.class.getName() + ".correlationId";

    /** Exchange attribute holding the path the client requested (survives the internal fallback forward). */
    public static final String ORIGINAL_PATH_ATTR = Problems.class.getName() + ".originalPath";

    private final ObjectMapper mapper;

    public Problems(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static String correlationId(ServerWebExchange exchange) {
        String id = exchange.getAttribute(CORRELATION_ATTR);
        if (id == null) {
            id = exchange.getRequest().getHeaders().getFirst(CorrelationIds.HEADER);
        }
        return CorrelationIds.isValid(id) ? id : null;
    }

    public Map<String, Object> body(ServerWebExchange exchange, HttpStatus status, String code, String title,
                                    String detail) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "https://oms.example/problems/" + code.toLowerCase().replace('_', '-'));
        p.put("title", title);
        p.put("status", status.value());
        p.put("detail", detail);
        p.put("instance", instance(exchange));
        p.put("code", code);
        p.put("correlationId", correlationId(exchange));
        p.put("timestamp", Instant.now().toString());
        p.put("errors", List.of());
        return p;
    }

    /** The path the client requested (not the internal {@code /fallback/..} forward target). */
    private static String instance(ServerWebExchange exchange) {
        String original = exchange.getAttribute(ORIGINAL_PATH_ATTR);
        return original != null ? original : exchange.getRequest().getPath().value();
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String title, String detail) {
        return write(exchange, status, code, title, detail, Map.of());
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String title, String detail,
                            Map<String, String> headers) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body(exchange, status, code, title, detail));
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(PROBLEM_JSON);
        response.getHeaders().setContentLength(bytes.length);
        headers.forEach((k, v) -> response.getHeaders().set(k, v));
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
