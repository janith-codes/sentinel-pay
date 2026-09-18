package com.sentinelpay.api.integration;

import com.sentinelpay.application.dto.AuthTokens;
import com.sentinelpay.application.port.out.PasswordHasherPort;
import com.sentinelpay.domain.identity.Role;
import com.sentinelpay.infrastructure.messaging.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for the Testcontainers integration tests.
 *
 * <p>Containers are held in static fields and started once per JVM rather than per class, so all
 * {@code *IT} classes share one PostgreSQL, Redis and RabbitMQ instance. Testcontainers' Ryuk
 * sidecar removes them when the JVM exits. Because every subclass shares this exact configuration,
 * Spring also reuses a single application context across them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
public abstract class AbstractIntegrationTest {

    protected static final String REDIS_KEY_PREFIX = "idempotency:";
    protected static final int REDIS_PORT = 6379;

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);

    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine"));

    static {
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @Autowired protected TestRestTemplate restTemplate;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected StringRedisTemplate stringRedisTemplate;
    @Autowired protected RabbitTemplate rabbitTemplate;
    @Autowired protected AmqpAdmin amqpAdmin;
    @Autowired protected PasswordHasherPort passwordHasher;

    /** Leaves the Flyway history intact so migrations are applied exactly once per JVM. */
    @BeforeEach
    void resetInfrastructureState() {
        jdbcTemplate.execute("TRUNCATE TABLE transactions, user_roles, users, accounts CASCADE");

        Set<String> keys = stringRedisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }

        amqpAdmin.purgeQueue(RabbitMQConfig.QUEUE, false);
    }

    // ---------------------------------------------------------------- fixtures

    protected UUID insertAccount(String accountNumber, String balance) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO accounts (id, account_number, balance, status, version)
                        VALUES (?, ?, ?, 'ACTIVE', 0)
                        """,
                id, accountNumber, new BigDecimal(balance));
        return id;
    }

    protected UUID insertUser(String username, String rawPassword, Role role, UUID accountId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO users (id, username, password_hash, account_id, enabled)
                        VALUES (?, ?, ?, ?, TRUE)
                        """,
                id, username, passwordHasher.hash(rawPassword), accountId);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role.name());
        return id;
    }

    // ---------------------------------------------------------------- http helpers

    protected ResponseEntity<AuthTokens> loginFor(String username, String rawPassword) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"username":"%s","password":"%s"}""".formatted(username, rawPassword);

        return restTemplate.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, headers), AuthTokens.class);
    }

    protected String accessTokenFor(String username, String rawPassword) {
        ResponseEntity<AuthTokens> response = loginFor(username, rawPassword);
        assertThat(response.getBody()).as("login must succeed for %s", username).isNotNull();
        return response.getBody().accessToken();
    }

    protected HttpEntity<String> paymentRequest(String token, String idempotencyKey, UUID accountId, String amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        String body = """
                {"accountId":"%s","amount":%s,"currency":"LKR"}""".formatted(accountId, amount);

        return new HttpEntity<>(body, headers);
    }

    protected <T> ResponseEntity<T> postPayment(String token, String idempotencyKey, UUID accountId,
                                                String amount, Class<T> responseType) {
        return restTemplate.exchange("/api/v1/payments", HttpMethod.POST,
                paymentRequest(token, idempotencyKey, accountId, amount), responseType);
    }

    // ---------------------------------------------------------------- assertions support

    protected BigDecimal balanceOf(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }

    protected int countTransactions() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
    }
}
