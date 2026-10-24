package com.enterprise.oms.common.autoconfigure;

import com.enterprise.oms.common.error.GlobalExceptionHandler;
import com.enterprise.oms.common.error.ProblemWriter;
import com.enterprise.oms.common.web.CorrelationIdFilter;
import com.enterprise.oms.common.web.RequestSizeLimitFilter;
import com.enterprise.oms.common.web.WebProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** Servlet filters (correlation id, 64KB body limit) and the problem+json exception handler. Toggle: {@code oms.web.enabled}. */
@AutoConfiguration(after = OmsCoreAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "oms.web", name = "enabled", matchIfMissing = true)
public class OmsWebAutoConfiguration {

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> omsCorrelationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> reg = new FilterRegistrationBean<>(new CorrelationIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> omsRequestSizeLimitFilter(WebProperties props,
                                                                             ProblemWriter writer) {
        FilterRegistrationBean<RequestSizeLimitFilter> reg =
                new FilterRegistrationBean<>(new RequestSizeLimitFilter(props.getMaxBodyBytes(), writer));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean(GlobalExceptionHandler.class)
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
