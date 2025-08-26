package com.ihomziak.core.productsmicroservice.products.service;

import com.ihomziak.core.ProductCreatedEvent;
import com.ihomziak.core.productsmicroservice.products.rest.CreateProductRestModel;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Service
public class ProductServiceImpl implements ProductService {

    private final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);
    private final KafkaTemplate<String, ProductCreatedEvent> kafkaTemplate;

    @Autowired
    public ProductServiceImpl(KafkaTemplate<String, ProductCreatedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public String createProductAsync(CreateProductRestModel product) {

        String productId = UUID.randomUUID().toString();

        // TODO: Persist Product  Details into database before publish an event
        ProductCreatedEvent productCreatedEvent = new ProductCreatedEvent(productId, product.getTitle(), product.getPrice(), product.getQuantity());

        CompletableFuture<SendResult<String, ProductCreatedEvent>> future = kafkaTemplate.send("product-created-events-topic", productId, productCreatedEvent);

        future.whenComplete((result, exception) -> {
            if (exception != null) {
                log.error("********** Failed to send product created events to topic: {}", exception.getMessage());
            } else {
                log.info("********** Successfully send product created events to topic: {}", result.getRecordMetadata());
            }
        });

        log.info("********** Async. Returning product ID: {}", productId);
        return productId;
    }

    @Override
    public String createProductSync(CreateProductRestModel product) throws ExecutionException, InterruptedException {

        String productId = UUID.randomUUID().toString();

        // TODO: Persist Product  Details into database before publish an event
        ProductCreatedEvent productCreatedEvent = new ProductCreatedEvent(productId, product.getTitle(), product.getPrice(), product.getQuantity());

        log.info("***** Before publishing ProductCreatedEvent");

        ProducerRecord<String, ProductCreatedEvent> record = new ProducerRecord<>(
                "product-created-events-topic",
                productId,
                productCreatedEvent
        );
        record.headers().add("messageId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

        SendResult<String, ProductCreatedEvent> result = kafkaTemplate.send(record).get();

        log.info("Partition: {}", result.getRecordMetadata().partition());
        log.info("Topic: {}", result.getRecordMetadata().topic());
        log.info("Offset: {}", result.getRecordMetadata().offset());

        log.info("********** Sync. Returning product ID: {}, Result: {}", productId, result.getRecordMetadata());
        return productId;
    }
}
