# Lab 04: Spring Boot Pub/Sub Integration

**Duration:** 45 minutes
**Objective:** Add Pub/Sub messaging to the UPI Transfer Service — publish transaction events after each transfer and consume them with a subscriber.

---

## Prerequisites

- Lab 03 completed (`transaction-events` topic exists)
- UPI Transfer Service source code in Cloud Shell

---

## Part 1: Add Dependencies

Edit `pom.xml` to add the Spring Cloud GCP Pub/Sub starter:

```xml
<!-- Add inside <dependencies> section -->
<dependency>
    <groupId>com.google.cloud</groupId>
    <artifactId>spring-cloud-gcp-starter-pubsub</artifactId>
    <version>5.9.0</version>
</dependency>
<dependency>
    <groupId>com.google.cloud</groupId>
    <artifactId>spring-cloud-gcp-starter</artifactId>
    <version>5.9.0</version>
</dependency>
```

### Verify the dependency resolves

```bash
cd ~/upi-transfer-service
./mvnw dependency:resolve -q
echo "Dependencies resolved successfully"
```

---

## Part 2: Configure Pub/Sub

Add to `src/main/resources/application.properties`:

```properties
# Pub/Sub Configuration
spring.cloud.gcp.pubsub.subscriber.max-concurrency=5
spring.cloud.gcp.pubsub.publisher.batching.enabled=false
```

> On Cloud Run / Cloud Shell, GCP credentials are automatically provided. No API key needed.

---

## Part 3: Create the Transaction Event DTO

Create `src/main/java/com/example/upi/dto/TransactionEvent.java`:

```java
package com.example.upi.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransactionEvent {
    private Long transactionId;
    private String senderUpiId;
    private String receiverUpiId;
    private BigDecimal amount;
    private String status;
    private LocalDateTime timestamp;

    public TransactionEvent() {}

    public TransactionEvent(Long transactionId, String senderUpiId,
                            String receiverUpiId, BigDecimal amount,
                            String status) {
        this.transactionId = transactionId;
        this.senderUpiId = senderUpiId;
        this.receiverUpiId = receiverUpiId;
        this.amount = amount;
        this.status = status;
        this.timestamp = LocalDateTime.now();
    }

    // Getters and setters
    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }
    public String getSenderUpiId() { return senderUpiId; }
    public void setSenderUpiId(String senderUpiId) { this.senderUpiId = senderUpiId; }
    public String getReceiverUpiId() { return receiverUpiId; }
    public void setReceiverUpiId(String receiverUpiId) { this.receiverUpiId = receiverUpiId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "TransactionEvent{txnId=" + transactionId +
               ", from=" + senderUpiId +
               ", to=" + receiverUpiId +
               ", amount=" + amount +
               ", status=" + status + "}";
    }
}
```

---

## Part 4: Create the Event Publisher

Create `src/main/java/com/example/upi/service/TransactionEventPublisher.java`:

```java
package com.example.upi.service;

import com.example.upi.dto.TransactionEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);
    private static final String TOPIC = "transaction-events";

    private final PubSubTemplate pubSubTemplate;
    private final ObjectMapper objectMapper;

    public TransactionEventPublisher(PubSubTemplate pubSubTemplate,
                                     ObjectMapper objectMapper) {
        this.pubSubTemplate = pubSubTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishTransactionEvent(TransactionEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            pubSubTemplate.publish(TOPIC, json)
                .whenComplete((id, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish event: {}", event, ex);
                    } else {
                        log.info("Published event to {}: txnId={}, messageId={}",
                                TOPIC, event.getTransactionId(), id);
                    }
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event: {}", event, e);
        }
    }
}
```

---

## Part 5: Integrate Publisher into TransferService

Open `src/main/java/com/example/upi/service/TransferService.java` and add the publisher.

Add the field and constructor parameter:

```java
private final TransactionEventPublisher eventPublisher;

// Update constructor to include eventPublisher
public TransferService(AccountRepository accountRepository,
                       TransactionRepository transactionRepository,
                       TransactionEventPublisher eventPublisher) {
    this.accountRepository = accountRepository;
    this.transactionRepository = transactionRepository;
    this.eventPublisher = eventPublisher;
}
```

After a successful transfer (where the transaction is saved), add:

```java
// Publish event after successful transfer
TransactionEvent event = new TransactionEvent(
    savedTransaction.getId(),
    request.getSenderUpiId(),
    request.getReceiverUpiId(),
    request.getAmount(),
    savedTransaction.getStatus().name()
);
eventPublisher.publishTransactionEvent(event);
```

> The publish is asynchronous — it does NOT block the transfer response.

---

## Part 6: Create the Event Subscriber

Create `src/main/java/com/example/upi/service/TransactionEventSubscriber.java`:

```java
package com.example.upi.service;

import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.cloud.spring.pubsub.support.BasicAcknowledgeablePubsubMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TransactionEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventSubscriber.class);
    private static final String SUBSCRIPTION = "transaction-processor";

    private final PubSubTemplate pubSubTemplate;

    public TransactionEventSubscriber(PubSubTemplate pubSubTemplate) {
        this.pubSubTemplate = pubSubTemplate;
    }

    @PostConstruct
    public void subscribe() {
        log.info("Subscribing to: {}", SUBSCRIPTION);

        pubSubTemplate.subscribe(SUBSCRIPTION, message -> {
            String payload = message.getPubsubMessage()
                .getData()
                .toStringUtf8();

            log.info("Received transaction event: {}", payload);

            // In a real app, this would:
            // - Send push notification to user
            // - Update fraud detection system
            // - Write to analytics pipeline

            // Acknowledge the message
            message.ack();
            log.info("Message acknowledged: {}", message.getPubsubMessage().getMessageId());
        });
    }
}
```

---

## Part 7: Test Locally with Pub/Sub Emulator

### Start the emulator

```bash
# In a separate Cloud Shell terminal
gcloud beta emulators pubsub start --project=test-project --host-port=0.0.0.0:8085
```

### Set environment variables (in your main terminal)

```bash
export PUBSUB_EMULATOR_HOST=localhost:8085

# Create the topic and subscription on the emulator
curl -X PUT "http://localhost:8085/v1/projects/test-project/topics/transaction-events"
curl -X PUT "http://localhost:8085/v1/projects/test-project/subscriptions/transaction-processor" \
  -H "Content-Type: application/json" \
  -d '{"topic":"projects/test-project/topics/transaction-events"}'
```

### Run the app locally

```bash
cd ~/upi-transfer-service
SPRING_CLOUD_GCP_PROJECT_ID=test-project ./mvnw spring-boot:run
```

### Test a transfer

```bash
# In another terminal (with PUBSUB_EMULATOR_HOST set)
curl -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":100}'
```

### Check the app logs

You should see both the publish and subscribe logs:

```
Published event to transaction-events: txnId=1, messageId=...
Received transaction event: {"transactionId":1,"senderUpiId":"nag@upi",...}
Message acknowledged: ...
```

> The emulator avoids Pub/Sub charges and doesn't require authentication.

---

## Part 8: Deploy to Cloud Run with Real Pub/Sub

### Unset the emulator variable

```bash
unset PUBSUB_EMULATOR_HOST
```

### Ensure the topic and subscription exist

```bash
gcloud pubsub topics describe transaction-events
gcloud pubsub subscriptions describe transaction-processor
```

### Build and deploy

```bash
cd ~/upi-transfer-service

# If using Cloud Build (from Lab 01)
gcloud builds submit --config=cloudbuild.yaml --substitutions=SHORT_SHA=pubsub01 .

# Or manual deploy
./mvnw clean package -DskipTests
docker build -t asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:pubsub .
docker push asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:pubsub
gcloud run deploy upi-transfer-service \
  --image=asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:pubsub \
  --region=asia-south1 \
  --allow-unauthenticated
```

### Test on Cloud Run

```bash
export SERVICE_URL=$(gcloud run services describe upi-transfer-service \
  --region asia-south1 --format='value(status.url)')

curl -X POST $SERVICE_URL/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":250}'
```

### View the logs

```bash
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="upi-transfer-service" AND "Published event"' \
  --limit=5 \
  --format="table(timestamp, textPayload)"
```

---

## Checkpoint

- [ ] Added `spring-cloud-gcp-starter-pubsub` dependency
- [ ] Created `TransactionEvent` DTO
- [ ] Created `TransactionEventPublisher` — publishes after each transfer
- [ ] Integrated publisher into `TransferService`
- [ ] Created `TransactionEventSubscriber` — listens and logs events
- [ ] Tested locally with Pub/Sub emulator
- [ ] Deployed to Cloud Run and verified with real Pub/Sub
