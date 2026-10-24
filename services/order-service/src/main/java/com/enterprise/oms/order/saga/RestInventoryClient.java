package com.enterprise.oms.order.saga;

import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.http.OmsRestClientFactory;
import com.enterprise.oms.order.config.InventoryProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * REST implementation. Transport comes from {@link OmsRestClientFactory#createServiceClient} (timeouts 2s/3s,
 * X-Correlation-Id, SERVICE bearer token). Resilience4j: retry (3 attempts, exponential backoff, only transient
 * failures) inside a circuit breaker window. Instance config: {@code resilience4j.*.instances.inventory}.
 * The base URL is {@code oms.inventory.base-url} (env OMS_INVENTORY_BASE_URL).
 */
@Component
public class RestInventoryClient implements InventoryClient {

    private static final String VALIDATE_PATH = "/api/v1/internal/products/validate";

    private final RestClient client;

    public RestInventoryClient(OmsRestClientFactory factory, InventoryProperties props) {
        this.client = factory.createServiceClient(props.getBaseUrl());
    }

    record ValidateRequest(List<EventPayloads.QuantityItem> items) {
    }

    @Override
    @Retry(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public ValidationResult validate(List<EventPayloads.QuantityItem> items) {
        try {
            ValidationResult result = client.post().uri(VALIDATE_PATH).contentType(MediaType.APPLICATION_JSON)
                    .body(new ValidateRequest(items)).retrieve().body(ValidationResult.class);
            if (result == null) {
                throw new InventoryUnavailableException("inventory returned an empty body", null);
            }
            return result;
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.is5xxServerError() || status.value() == 429 || status.value() == 408) {
                throw new InventoryUnavailableException("inventory answered HTTP " + status.value(), e);
            }
            throw new InventoryCallException("inventory rejected the call with HTTP " + status.value(), e);
        } catch (RestClientException e) {
            // connect/read timeouts, connection refused, unreadable body
            throw new InventoryUnavailableException("inventory not reachable: " + e.getClass().getSimpleName(), e);
        }
    }
}
