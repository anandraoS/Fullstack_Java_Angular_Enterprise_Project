package com.enterprise.oms.common.http;

import com.enterprise.oms.common.security.ServiceTokenProvider;
import com.enterprise.oms.common.web.CorrelationIds;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builds {@link RestClient}s with the contract's outbound timeouts (connect 2s, read 3s), the correlation id
 * forwarded from the MDC, and optionally a SERVICE bearer token for {@code /api/v1/internal/**} calls.
 * Pair calls with Resilience4j (circuit breaker + retry) in the caller; this factory only does transport hygiene.
 */
public class OmsRestClientFactory {

    private final HttpClientProperties props;
    private final ServiceTokenProvider tokens;

    public OmsRestClientFactory(HttpClientProperties props, ServiceTokenProvider tokens) {
        this.props = props;
        this.tokens = tokens;
    }

    /** Plain client: timeouts + correlation id. */
    public RestClient create(String baseUrl) {
        return builder(baseUrl, false).build();
    }

    /** Client that adds {@code Authorization: Bearer <service token>} (service-to-service). */
    public RestClient createServiceClient(String baseUrl) {
        return builder(baseUrl, true).build();
    }

    public RestClient.Builder builder(String baseUrl, boolean serviceAuth) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) props.getConnectTimeout().toMillis());
        rf.setReadTimeout((int) props.getReadTimeout().toMillis());
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl).requestFactory(rf)
                .requestInterceptor((request, body, execution) -> {
                    String id = CorrelationIds.currentOrCreate();
                    request.getHeaders().set(CorrelationIds.HEADER, id);
                    if (serviceAuth && tokens != null) {
                        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token());
                    }
                    return execution.execute(request, body);
                });
        return b;
    }

    public Duration connectTimeout() {
        return props.getConnectTimeout();
    }

    public Duration readTimeout() {
        return props.getReadTimeout();
    }
}
