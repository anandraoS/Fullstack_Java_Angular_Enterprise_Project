package com.enterprise.oms.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling   // recovery, reconciliation and idempotency cleanup jobs (platform-common enables it too; harmless twice)
@EnableConfigurationProperties({SagaProperties.class, InventoryProperties.class, ReconciliationProperties.class,
        RateLimitProperties.class, IdempotencyProperties.class})
public class OrderServiceConfig {

    /**
     * Runs the saga's first step (validation: a REST call) right after the order commits, off the request thread.
     * Rejections (queue full) are harmless: the SagaRecoveryJob re-drives any order still in CREATED.
     * Graceful shutdown waits for running validations.
     */
    @Bean(name = "omsSagaExecutor")
    ThreadPoolTaskExecutor omsSagaExecutor(SagaProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("saga-");
        ex.setCorePoolSize(props.getExecutorCoreSize());
        ex.setMaxPoolSize(props.getExecutorMaxSize());
        ex.setQueueCapacity(props.getExecutorQueueCapacity());
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(20);
        return ex;
    }
}
