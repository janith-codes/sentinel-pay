package com.sentinelpay.infrastructure.messaging.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE   = "sentinel-pay.exchange";
    public static final String QUEUE      = "payment.processed.queue";
    public static final String ROUTING_KEY = "payment.processed";

    @Bean
    TopicExchange sentinelPayExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    Queue paymentProcessedQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    Binding paymentProcessedBinding(Queue paymentProcessedQueue, TopicExchange sentinelPayExchange) {
        return BindingBuilder.bind(paymentProcessedQueue)
                .to(sentinelPayExchange)
                .with(ROUTING_KEY);
    }

    @Bean
    MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
