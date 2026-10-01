package com.sentinelpay.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.Ordered;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication(scanBasePackages = "com.sentinelpay")
@ConfigurationPropertiesScan(basePackages = "com.sentinelpay")
// Must stay ahead of the transaction advisor (Ordered.LOWEST_PRECEDENCE) so that a retried
// attempt begins a new transaction instead of reusing the one that just rolled back.
@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)
public class SentinelPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelPayApplication.class, args);
    }

}
