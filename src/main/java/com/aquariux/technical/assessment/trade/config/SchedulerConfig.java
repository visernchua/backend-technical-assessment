package com.aquariux.technical.assessment.trade.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.*;

@Configuration
public class SchedulerConfig {
    @Bean
    RestTemplate restTemplate(
            @Value("${trading.pricing.connect-timeout-ms:200}") int connect,
            @Value("${trading.pricing.read-timeout-ms:300}") int read) {
        if (connect <= 0 || read <= 0)
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return new RestTemplate(factory);
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService priceExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(
            name = "trading.pricing.enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class SchedulingEnabled {}
}
