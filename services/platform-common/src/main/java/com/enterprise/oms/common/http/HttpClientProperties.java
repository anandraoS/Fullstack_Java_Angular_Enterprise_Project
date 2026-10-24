package com.enterprise.oms.common.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code oms.http.*}: outbound REST timeouts (contract: connect 2s, read 3s). */
@ConfigurationProperties(prefix = "oms.http")
public class HttpClientProperties {

    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(3);

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
