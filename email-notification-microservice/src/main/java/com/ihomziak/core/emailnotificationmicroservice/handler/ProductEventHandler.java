package com.ihomziak.core.emailnotificationmicroservice.handler;

import com.ihomziak.core.core.ProductCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@KafkaListener(topics = "product-created-events-topic")
public class ProductEventHandler {

    private final Logger log = LoggerFactory.getLogger(ProductEventHandler.class);

    @KafkaHandler
    public void handle(ProductCreatedEvent productCreatedEvent) {
        log.info("Received a new event {}", productCreatedEvent);
    }
}
