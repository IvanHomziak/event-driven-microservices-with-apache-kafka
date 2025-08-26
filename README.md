
---

# Event-Driven Microservices with Apache Kafka (Spring Boot 3)

**TL;DR**
This repository contains a set of Spring Boot microservices that communicate via **Apache Kafka**. It demonstrates three pillars of reliable event processing:

1. **Idempotent Consumers** — prevent double business execution using a unique `messageId` + DB de-dup table.
2. **Kafka Transactions** — atomically send to multiple topics (all-or-nothing) and enable Exactly-Once semantics in *consume → process → produce* flows.
3. **Well-behaved Retries** — separate *retryable* vs *not-retryable* errors and route failed records to **DLT**.

---

## Services

* **products-microservice**
  REST API to create a product. Publishes `ProductCreatedEvent` to Kafka.
  Adds a unique `messageId` in message **headers**. Producer is idempotent; transactions are enabled so you can atomically emit to multiple topics when needed.

* **email-notification-microservice**
  Consumes `ProductCreatedEvent`.
  Reads `messageId` from headers, performs an **early duplicate check** in DB (H2 for dev / MySQL for prod), executes the business action (HTTP call to a mock service), then **persists `messageId`** into `processed_event` (with a unique index). Includes `RetryableException` / `NotRetryableException` and a `DefaultErrorHandler` wired to DLT.

* **transfers-microservice**
  REST API to initiate a money transfer.
  In **one Kafka transaction**, sends both `WithdrawalRequestedEvent` (→ `withdraw-money-topic`) and `DepositRequestedEvent` (→ `deposit-money-topic`). Consumers with `read_committed` will see **both** events together or **none** (transaction abort).

* **withdrawal-microservice**
  Consumes `WithdrawalRequestedEvent` with `isolation.level=read_committed`.

* **deposit-microservice**
  Consumes `DepositRequestedEvent` with `isolation.level=read_committed`.

> Brokers are typically referenced as `localhost:9092,localhost:9094` across services.

---

## Events, Topics, Headers

### Payload DTOs

* `ProductCreatedEvent`
* `WithdrawalRequestedEvent`
* `DepositRequestedEvent`

### Topics (override via properties if needed)

* `product-created` (or `product-created-events`, depending on your config)
* `withdraw-money-topic`
* `deposit-money-topic`
* DLT convention: `${topic}.DLT`

### Standard Headers

* `messageId` — **unique identifier** of the event (UTF-8 `String`).
* `correlationId` — end-to-end tracing.
* `eventType`, `schemaVersion` — technical metadata.

---

## Idempotent Consumer (email-notification)

**Goal:** if the same event arrives again, **do not** repeat business logic.

Algorithm:

1. Read `messageId` from headers.
2. **Early exit**: `existsByMessageId(messageId)` → **skip** and commit offset.
3. On first processing: execute business logic (e.g., call mock HTTP service), then **persist `messageId`** and business key to DB.
4. If a unique constraint violation occurs at the end, treat it as a **duplicate**:

    * either silently **skip** (no retry, no DLT), or
    * throw `NotRetryableException` (goes to DLT) — pick a policy and keep it consistent.

**Table `processed_event`:**

```sql
CREATE TABLE processed_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id VARCHAR(64) NOT NULL UNIQUE,
  product_id VARCHAR(64) NOT NULL,
  processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_processed_event_message_id ON processed_event (message_id);
```

**Why this survives redelivery:** offset is committed **after** business logic. If the instance dies after DB commit but before the offset commit, the next delivery will see `messageId` already stored and **skip** work.

---

## Exactly-Once in Kafka Chains

Kafka provides:

* **Idempotent Producer** (`enable.idempotence=true`, `acks=all`, `max.in.flight=5`) to avoid duplicates on producer retries.
* **Transactions** (`transaction-id-prefix=...`) to make multiple sends **atomic** and (in *consume → process → produce* flows) commit offsets **in the same transaction**.
* **read\_committed** consumers to avoid seeing uncommitted records.

**Transfers service** uses a transactional producer: `withdraw` + `deposit` are emitted **atomically**. If anything fails, the transaction **aborts** and consumers will see **neither** event.

> Note: HTTP calls and DB writes are **not** part of a Kafka transaction. Use the Outbox pattern or compensating transactions for cross-service consistency (out of scope for this demo).

---

## Error Handling & DLT

`DefaultErrorHandler` + `DeadLetterPublishingRecoverer` is configured so that:

* `RetryableException` → retried with backoff.
* `NotRetryableException` → **no retries**, sent to `${topic}.DLT`.
* `DataIntegrityViolationException` (on `messageId`) can be treated as **not-retryable** to avoid noisy loops on duplicates.

---

## Database

* **Dev:** in-memory **H2**
  `jdbc:h2:mem:emaildb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE`
  H2 console: `http://localhost:8080/h2-console`
  Credentials: as defined in your `application.properties` (commonly `dev`/`dev`).

* **Prod:** **MySQL** (recommended) + **Flyway** migrations.

---

## Configuration Quick Reference

### Producer (products / transfers)

```properties
spring.kafka.producer.bootstrap-servers=localhost:9092,localhost:9094
spring.kafka.producer.acks=all
spring.kafka.producer.properties.enable.idempotence=true
spring.kafka.producer.properties.max.in.flight.requests.per.connection=5
spring.kafka.producer.transaction-id-prefix=<service>-tx-
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
```

### Consumer (email-notification / withdrawal / deposit)

```properties
spring.kafka.consumer.bootstrap-servers=localhost:9092,localhost:9094
spring.kafka.consumer.group-id=<group>
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.isolation-level=read_committed
spring.kafka.listener.ack-mode=MANUAL
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
spring.kafka.consumer.properties.spring.json.trusted.packages=<your.payload.package>
```

### H2 (dev)

```properties
spring.datasource.url=jdbc:h2:mem:emaildb;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE
spring.datasource.username=dev
spring.datasource.password=dev
spring.datasource.driver-class-name=org.h2.Driver

spring.jpa.hibernate.ddl-auto=none
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console
```

---

## Running Locally

### 0) Start Kafka (2 advertised listeners)

**Minimal `docker-compose` (KRaft, single broker exposing two client listeners):**

```yaml
version: '3.8'
services:
  kafka:
    image: bitnami/kafka:3.7
    environment:
      - KAFKA_ENABLE_KRAFT=yes
      - KAFKA_CFG_NODE_ID=1
      - KAFKA_CFG_PROCESS_ROLES=broker,controller
      - KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER
      - KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,PLAINTEXT2://:9094,CONTROLLER://:9093
      - KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092,PLAINTEXT2://localhost:9094,CONTROLLER://localhost:9093
      - KAFKA_CFG_INTER_BROKER_LISTENER_NAME=PLAINTEXT
      - KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=1@localhost:9093
      - KAFKA_CFG_OFFSETS_TOPIC_REPLICATION_FACTOR=1
      - KAFKA_CFG_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1
      - KAFKA_CFG_TRANSACTION_STATE_LOG_MIN_ISR=1
    ports:
      - "9092:9092"
      - "9094:9094"
```

> Transactions require the internal `__transaction_state` topics; the above config makes them usable for local dev.

### 1) Build

```bash
mvn -q -DskipTests clean package
```

### 2) Run services (each in its own terminal)

```bash
# products (port per your properties)
java -jar products-microservice/target/*.jar

# email-notification (H2 + /h2-console)
java -jar email-notification-microservice/target/*.jar

# transfers
java -jar transfers-microservice/target/*.jar

# withdrawal
java -jar withdrawal-microservice/target/*.jar

# deposit
java -jar deposit-microservice/target/*.jar
```

---

## Calling the APIs

### Create a product

```bash
curl -X POST http://localhost:8080/products \
  -H "Content-Type: application/json" \
  -d '{
        "title": "MacBook Pro 14",
        "price": 2499.00
      }'
```

Expected: products service emits `ProductCreatedEvent` with a `messageId` header; email-notification consumes it, calls the mock service (commonly on **:8083**), then stores `messageId` in H2.

### Create a transfer

```bash
curl -X POST http://localhost:8082/transfers \
  -H "Content-Type: application/json" \
  -d '{
        "fromAccountId": "A-1001",
        "toAccountId": "B-2002",
        "amount": 150.00
      }'
```

Expected: transfers service sends **both** `WithdrawalRequestedEvent` and `DepositRequestedEvent` in a **single transaction**. Consumers see both only after commit.

---

## Observability

Enable DEBUG/TRACE for:

* `org.apache.kafka.clients.producer`
* `org.springframework.kafka.transaction`
* `org.springframework.transaction`

Spring Boot Actuator can expose `/actuator/health` and `/actuator/info` (keep only those publicly exposed that you need).

---

## Suggested Project Layout (example)

```
.
├── products-microservice
│   ├── controller/ProductController.java
│   ├── service/{ProductService, ProductServiceImpl}.java
│   ├── config/KafkaConfig.java
│   ├── model/{CreateProductRestModel, ProductCreatedEvent}.java
│   └── application.properties
├── email-notification-microservice
│   ├── handler/ProductCreatedEventHandler.java
│   ├── persistence/{ProcessedEventEntity, ProcessedEventRepository}.java
│   ├── config/KafkaConsumerConfiguration.java
│   ├── exception/{RetryableException, NotRetryableException}.java
│   └── application.properties
├── transfers-microservice
│   ├── controller/TransfersController.java
│   ├── service/{TransferService, TransferServiceImpl}.java
│   ├── config/KafkaConfig.java
│   ├── model/{TransferRestModel, WithdrawalRequestedEvent, DepositRequestedEvent}.java
│   └── application.properties
├── withdrawal-microservice
│   ├── handler/WithdrawalRequestedEventHandler.java
│   └── application.properties
└── deposit-microservice
    ├── handler/DepositRequestedEventHandler.java
    └── application.properties
```

*(Package names in your code may differ; keep them consistent with the `spring.kafka.consumer.properties.spring.json.trusted.packages` setting.)*

---

## Troubleshooting

* **Consumer sees “half” of a transaction.**
  Ensure `spring.kafka.consumer.isolation-level=read_committed`.

* **Duplicates in email-notification.**
  Ensure producer always sets `messageId` header and the table has `UNIQUE(message_id)`. Keep the early `existsByMessageId(...)` shortcut.

* **Too many DLT records due to duplicates.**
  Instead of throwing `NotRetryableException` on `DataIntegrityViolationException`, silently **skip** (log & return).

* **JsonDeserializer complains about package/class.**
  Set `spring.kafka.consumer.properties.spring.json.trusted.packages=<payload package>`.

* **Transactions “don’t work”.**
  Verify `spring.kafka.producer.transaction-id-prefix` is set, check `Begin/Commit/Abort` logs, confirm internal transaction topics exist, and keep `acks=all` + idempotence.

---

## Limitations & Next Steps

* **DB + Kafka** are not coordinated here. In production, prefer **Outbox** (e.g., Debezium) or a coordinated transaction strategy (used carefully).
* **Schema management** (Avro/JSON Schema/Registry) is not included.
* **Security** (TLS/SASL/OAuth2) is out of scope.
* Observability can be extended with **Micrometer + Prometheus/Grafana**.

---

## Summary

* **Products** emits events with a unique `messageId` header.
* **Email-notification** is an **idempotent consumer** that records processed `messageId`s in DB.
* **Transfers** showcases **Kafka transactions**: *withdraw + deposit* are published atomically.
* **Withdrawal/Deposit** consume with **read\_committed**.

