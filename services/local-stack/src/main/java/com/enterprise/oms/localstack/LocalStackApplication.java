package com.enterprise.oms.localstack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.CountDownLatch;

/**
 * Starts the Docker-free local infrastructure (embedded KRaft Kafka on {@code localhost:19092}) and stays alive
 * until the process is stopped (Ctrl+C / SIGTERM). Configure with {@code --local-stack.kafka.port=NNNN}.
 */
@SpringBootApplication
public class LocalStackApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(LocalStackApplication.class).web(WebApplicationType.NONE).run(args);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    LocalKafkaBroker localKafkaBroker(@Value("${local-stack.kafka.port:19092}") int port) {
        return new LocalKafkaBroker(port);
    }

    /** Keeps the JVM alive; released when the context is closed by the shutdown hook. */
    @Bean(destroyMethod = "countDown")
    CountDownLatch keepAliveLatch() {
        return new CountDownLatch(1);
    }

    @Bean
    ApplicationRunner holdUntilShutdown(LocalKafkaBroker broker, CountDownLatch latch) {
        return (ApplicationArguments args) -> {
            System.out.println("local-stack ready: Kafka bootstrap servers = " + broker.bootstrapServers());
            latch.await();
        };
    }
}
