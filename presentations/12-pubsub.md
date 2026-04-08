# Google Pub/Sub — Event-Driven Architecture
## Decouple Services. Scale Independently. React in Real Time.

---

## The Problem — Why Sync Calls Don't Scale
```
Transfer Service → HTTP call → Notification Service
                 → HTTP call → Audit Service
                 → HTTP call → Analytics Service
```

### What Goes Wrong
- Notification service is **slow** → Transfer service waits, user waits
- Audit service is **down** → Transfer fails even though payment succeeded
- New consumer needed → **Change and redeploy** Transfer service
- Traffic spike → **All services must scale together** or requests fail

**Tight coupling between services = fragile, slow, hard to evolve.**

---

## What is Google Cloud Pub/Sub?
**A fully managed, real-time messaging service for event-driven architectures.**

### Key Properties
| Property | What It Means |
|---|---|
| **Fully managed** | No brokers to provision, no clusters to maintain |
| **At-least-once delivery** | Every message is delivered at least once |
| **Real-time** | Messages delivered in milliseconds |
| **Global** | Works across regions automatically |
| **Serverless** | Scales from 0 to millions of messages/sec |

**Think of it as a postal system for your microservices — drop a message, the right services receive it.**

---

## Core Concepts
### Topic
A named channel where publishers send messages. Like a radio station.

### Subscription
A named attachment to a topic. Each subscription gets its own copy of every message.

### Message
The data payload — a JSON body + optional attributes (key-value metadata).

### Ack / Nack
| Action | Meaning | Result |
|---|---|---|
| **Ack** | "Got it, processed successfully" | Message removed from subscription |
| **Nack** | "Failed, try again" | Message redelivered after deadline |
| **No response** | Subscriber crashed or timed out | Message redelivered automatically |

---

## How Pub/Sub Works
```
Transfer Service (Publisher)
        ↓
   [ Topic: transaction-completed ]
        ↓                    ↓
 Subscription A          Subscription B
        ↓                    ↓
 Notification Service    Audit Service
```

### The Flow
1. Transfer Service **publishes** a message to the `transaction-completed` topic
2. Pub/Sub **stores** the message and **fans out** to all subscriptions
3. Each subscriber **pulls** (or gets pushed) the message independently
4. Subscriber **acknowledges** after successful processing

**Publishers don't know about subscribers. Subscribers don't know about publishers. Total decoupling.**

---

## Pull vs Push Subscriptions
| Feature | Pull | Push |
|---|---|---|
| **How it works** | Subscriber polls Pub/Sub for messages | Pub/Sub sends HTTP POST to subscriber endpoint |
| **Control** | Subscriber controls the rate | Pub/Sub controls the rate |
| **Best for** | Long-running consumers, batch processing | Cloud Run, Cloud Functions, webhooks |
| **Scaling** | You manage concurrency | Auto-scales with push config |
| **Setup** | Subscriber needs Pub/Sub client library | Subscriber needs an HTTPS endpoint |

### When to Use Which
- **Pull** → Spring Boot services running on VMs or GKE with steady processing
- **Push** → Cloud Run services that scale to zero and wake on demand

---

## Pub/Sub vs Kafka vs RabbitMQ
| Feature | Google Pub/Sub | Apache Kafka | RabbitMQ |
|---|---|---|---|
| **Managed** | Fully managed | Self-managed (or Confluent) | Self-managed |
| **Ordering** | With ordering keys | Per partition | Per queue |
| **Replay** | Seek to timestamp | Full replay from offset | Limited |
| **Delivery** | At-least-once | At-least-once / Exactly-once | At-least-once |
| **Scaling** | Automatic | Manual partition scaling | Manual |
| **Latency** | ~100ms | ~10ms | ~1ms |
| **Best for** | GCP-native, serverless | High-throughput streaming | Complex routing patterns |
| **Ops burden** | None | High | Medium |

**On GCP, Pub/Sub is the default choice unless you need Kafka-specific features like log compaction or ultra-low latency.**

---

## UPI Service Use Case
### Current: Synchronous (Fragile)
```
POST /api/transfers → save to DB → call notification API → respond
```
If notification API is down, the transfer request fails or hangs.

### Improved: Event-Driven (Resilient)
```
POST /api/transfers → save to DB → publish event → respond immediately
                                         ↓
                                   [ Topic: upi-transaction-completed ]
                                     ↓              ↓              ↓
                              Notification     Fraud Check      Analytics
                                Service         Service          Service
```

### What Changes
| Concern | Before | After |
|---|---|---|
| Transfer latency | Waits for all downstream calls | Returns immediately after publish |
| Downstream failure | Transfer fails | Event retried independently |
| Adding new consumers | Redeploy Transfer Service | Just create a new subscription |

---

## Spring Boot + Pub/Sub Integration
### Dependencies
```xml
<dependency>
    <groupId>com.google.cloud</groupId>
    <artifactId>spring-cloud-gcp-starter-pubsub</artifactId>
</dependency>
```

### Configuration — application.yml
```yaml
spring:
  cloud:
    gcp:
      project-id: ford-upi-project
      pubsub:
        subscriber:
          max-concurrency: 5
        publisher:
          batching:
            enabled: true
            element-count-threshold: 10
            delay-threshold-seconds: 1
```

### Create Topic & Subscription (CLI)
```bash
gcloud pubsub topics create upi-transaction-completed

gcloud pubsub subscriptions create notification-sub \
  --topic=upi-transaction-completed \
  --ack-deadline=30
```

---

## Publishing Messages from TransferService
```java
@Service
public class TransferService {

    private final PubSubTemplate pubSubTemplate;
    private final ObjectMapper objectMapper;

    public TransferService(PubSubTemplate pubSubTemplate,
                           ObjectMapper objectMapper) {
        this.pubSubTemplate = pubSubTemplate;
        this.objectMapper = objectMapper;
    }

    public TransferResponse executeTransfer(TransferRequest request) {
        // 1. Execute the transfer and save to DB
        Transfer transfer = processTransfer(request);

        // 2. Publish event asynchronously — does NOT block the response
        TransactionEvent event = new TransactionEvent(
            transfer.getId(),
            transfer.getSenderVpa(),
            transfer.getReceiverVpa(),
            transfer.getAmount(),
            transfer.getStatus()
        );

        pubSubTemplate.publish(
            "upi-transaction-completed",
            objectMapper.writeValueAsString(event)
        );

        // 3. Return response immediately
        return new TransferResponse(transfer.getId(), "SUCCESS");
    }
}
```

**The publish call is fire-and-forget. The transfer response does not depend on downstream consumers.**

---

## Subscribing to Messages
### Using PubSubInboundChannelAdapter (Spring Integration)
```java
@Configuration
public class PubSubSubscriberConfig {

    @Bean
    public PubSubInboundChannelAdapter inboundAdapter(
            PubSubTemplate pubSubTemplate,
            @Qualifier("transactionChannel") MessageChannel channel) {

        PubSubInboundChannelAdapter adapter =
            new PubSubInboundChannelAdapter(pubSubTemplate,
                "notification-sub");
        adapter.setOutputChannel(channel);
        adapter.setAckMode(AckMode.MANUAL);
        return adapter;
    }

    @Bean
    public MessageChannel transactionChannel() {
        return new PublishSubscribeChannel();
    }

    @ServiceActivator(inputChannel = "transactionChannel")
    public void handleEvent(Message<?> message) {
        String payload = (String) message.getPayload();
        TransactionEvent event = objectMapper
            .readValue(payload, TransactionEvent.class);

        // Send notification to user
        notificationService.sendPushNotification(
            event.getSenderVpa(),
            "Transfer of ₹" + event.getAmount() + " completed"
        );

        // Acknowledge only after successful processing
        BasicAcknowledgeablePubsubMessage ack =
            message.getHeaders().get(
                GcpPubSubHeaders.ORIGINAL_MESSAGE,
                BasicAcknowledgeablePubsubMessage.class);
        ack.ack();
    }
}
```

**Manual ack ensures messages are only removed after successful processing.**

---

## Pub/Sub with Cloud Run — Push Subscriptions
```
Pub/Sub → HTTPS POST → Cloud Run endpoint
```

### Setup
```bash
gcloud pubsub subscriptions create notification-push-sub \
  --topic=upi-transaction-completed \
  --push-endpoint=https://notification-service-abc123.a.run.app/pubsub/receive \
  --ack-deadline=30
```

### Cloud Run Endpoint
```java
@RestController
public class PubSubPushController {

    @PostMapping("/pubsub/receive")
    public ResponseEntity<Void> receiveMessage(
            @RequestBody PubSubPushMessage pushMessage) {

        String data = new String(
            Base64.getDecoder().decode(
                pushMessage.getMessage().getData()));

        TransactionEvent event = objectMapper
            .readValue(data, TransactionEvent.class);

        notificationService.process(event);

        // Return 200 = Pub/Sub treats as ACK
        // Return 4xx/5xx = Pub/Sub retries
        return ResponseEntity.ok().build();
    }
}
```

### Why Push + Cloud Run Works Well
| Benefit | Explanation |
|---|---|
| **Scale to zero** | No messages → no instances → no cost |
| **Auto-scale** | Burst of messages → Cloud Run spins up containers |
| **No polling** | Pub/Sub pushes; no client library needed in the subscriber |

---

## Best Practices
### Idempotency
Messages can be delivered more than once. Design consumers to handle duplicates.
```java
// Use message ID or transaction ID to deduplicate
if (processedEventRepository.existsById(event.getTransactionId())) {
    log.info("Duplicate event, skipping: {}", event.getTransactionId());
    ack.ack();
    return;
}
```

### Dead Letter Topics
Messages that fail repeatedly get routed to a dead letter topic for investigation.
```bash
gcloud pubsub subscriptions update notification-sub \
  --dead-letter-topic=upi-transaction-dead-letter \
  --max-delivery-attempts=5
```

### Ordering Keys
When message order matters (e.g., transfer → refund for same VPA), use ordering keys.
```java
pubSubTemplate.publish("upi-transaction-completed",
    payload, Map.of("orderingKey", senderVpa));
```

### Message Schema
Define and enforce schemas to prevent bad messages from entering the system.
```bash
gcloud pubsub schemas create transaction-event-schema \
  --type=AVRO \
  --definition-file=transaction-event.avsc
```

---

## Expert Takeaways
- **Pub/Sub decouples services** — publishers and subscribers evolve independently
- **At-least-once delivery** means you must design for idempotency
- **Push subscriptions + Cloud Run** = cost-efficient, serverless event processing
- **Dead letter topics are mandatory** in production — never lose failed messages silently
- **Start simple** — one topic, one subscription, manual ack. Add complexity only when needed.
- **Pub/Sub is the backbone** of event-driven architecture on GCP

---

## What's Next?
**Next:** Monitoring & Observability — how to track, alert, and debug your services in production using Cloud Monitoring, Cloud Logging, and distributed tracing.
