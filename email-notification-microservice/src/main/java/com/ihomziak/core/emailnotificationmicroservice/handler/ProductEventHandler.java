package com.ihomziak.core.emailnotificationmicroservice.handler;

import lombok.extern.slf4j.Slf4j;
import com.ihomziak.core.ProductCreatedEvent;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@KafkaListener(topics = "product-created-events-topic")
public class ProductEventHandler {

    @KafkaHandler
    public void handle(ProductCreatedEvent productCreatedEvent) {
        log.info("Received a new event {}", productCreatedEvent);
    }
}
