package com.sentinelpay.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.sentinelpay")
@ConfigurationPropertiesScan(basePackages = "com.sentinelpay")
public class SentinelPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelPayApplication.class, args);
    }

}
