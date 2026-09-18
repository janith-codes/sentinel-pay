package com.sentinelpay.api;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;NON_KEYWORDS=STATUS,CURRENCY,VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.ai.openai.api-key=test-key-not-real",
})
class SentinelPayApplicationTests {

    @MockBean
    ConnectionFactory connectionFactory;

    @MockBean
    RabbitTemplate rabbitTemplate;

    @MockBean
    StringRedisTemplate stringRedisTemplate;

    @Test
    void contextLoads() {
    }
}
