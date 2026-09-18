package com.sentinelpay.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.sentinelpay")
@EnableJpaRepositories(basePackages = "com.sentinelpay.infrastructure.persistence.repository")
@EntityScan(basePackages = "com.sentinelpay.infrastructure.persistence.entity")
public class SentinelPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelPayApplication.class, args);
    }

}
