package com.enterprise.oms.common.autoconfigure;

import com.enterprise.oms.common.error.ProblemWriter;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.http.HttpClientProperties;
import com.enterprise.oms.common.http.OmsRestClientFactory;
import com.enterprise.oms.common.security.ServiceTokenProvider;
import com.enterprise.oms.common.web.PageRequestFactory;
import com.enterprise.oms.common.web.WebProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/** Always-on basics: Jackson tweaks, ProblemWriter, PageRequestFactory, RestClient factory. */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@EnableConfigurationProperties({WebProperties.class, HttpClientProperties.class})
public class OmsCoreAutoConfiguration {

    /** Tolerant reader, ISO dates, JavaTimeModule for the Spring-managed ObjectMapper. */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer omsJacksonCustomizer() {
        return builder -> builder.postConfigurer(EventJson::configure);
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemWriter problemWriter(ObjectMapper mapper) {
        return new ProblemWriter(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    PageRequestFactory pageRequestFactory(WebProperties props) {
        return new PageRequestFactory(props.getMaxPageSize(), props.getDefaultPageSize());
    }

    @Bean
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnMissingBean
    OmsRestClientFactory omsRestClientFactory(HttpClientProperties props,
                                              ObjectProvider<ServiceTokenProvider> tokens) {
        return new OmsRestClientFactory(props, tokens.getIfAvailable());
    }
}
