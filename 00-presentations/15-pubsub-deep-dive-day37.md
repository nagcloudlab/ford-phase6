# Pub/Sub Deep Dive — Day 37
## Pub/Sub Foundations with Microservices
### Ford Phase 6 Training | Trainer: Nag

---

## Agenda

| # | Topic | Time |
|---|-------|------|
| 1 | The Problem — Why Sync REST Breaks at Scale | 15 min |
| 2 | Pub/Sub Mental Model — The Post Office Analogy | 10 min |
| 3 | Core Concepts Deep Dive | 20 min |
| 4 | Fan-Out Pattern — Our UPI Architecture | 15 min |
| 5 | Message Attributes — Metadata That Matters | 10 min |
| 6 | Pull vs Push Subscriptions | 10 min |
| 7 | The Emulator — Local Dev Without GCP | 10 min |
| 8 | The Four Factors — Throughput, Latency, Durability, Availability | 25 min |
| 9 | Live Demo — End-to-End Message Flow | 20 min |
| 10 | What's Coming Tomorrow | 5 min |

---

## 1. The Problem — Synchronous REST Between Microservices

### UPI in India — The Scale We're Talking About

| Metric | Number |
|--------|--------|
| UPI transactions (March 2024) | **13.44 billion** |
| Daily average | ~450 million txns/day |
| Peak second (estimated) | ~20,000 TPS |
| Total value (March 2024) | ₹19.64 lakh crore (~$235 billion) |
| Active apps (PhonePe, GPay, etc.) | 50+ |

> India processes more real-time digital payments than the US, UK, and EU **combined**.

---

### The Synchronous World — How It Starts

```
     User App             UPI Service           Notification        Fraud Check         Analytics
        |                     |                      |                   |                  |
        |--- POST /transfer ->|                      |                   |                  |
        |                     |                      |                   |                  |
        |                     |--- POST /notify ----->|                  |                  |
        |                     |      (wait 200ms)     |                  |                  |
        |                     |<---- 200 OK ----------|                  |                  |
        |                     |                       |                  |                  |
        |                     |--- POST /check-fraud ------------------>|                  |
        |                     |      (wait 300ms)                       |                  |
        |                     |<---- 200 OK ----------------------------|                  |
        |                     |                       |                  |                  |
        |                     |--- POST /record-analytics -------------------------------->|
        |                     |      (wait 150ms)                                          |
        |                     |<---- 200 OK ------------------------------------------------|
        |                     |                       |                  |                  |
        |<--- 200 OK ---------|                       |                  |                  |
        |                     |                       |                  |                  |

                    Total response time = 200 + 300 + 150 = 650ms (best case)
```

---

### What Goes Wrong at Scale?

**Problem 1: Latency Stacks Up**
- User waits for ALL downstream calls to finish
- 3 services = 3x the wait
- At UPI scale (450M txns/day), every ms matters

**Problem 2: Tight Coupling**
- If Notification Service is down, does the transfer fail?
- Should it? The money already moved!
- One slow service makes EVERYTHING slow

**Problem 3: No Failure Isolation**
- Analytics DB is full? Payment fails.
- Fraud detection has a bug? Payment fails.
- Notification gateway rate-limited? Payment fails.

**Problem 4: Scaling Mismatch**
- Transfer service handles 10,000 TPS during Diwali
- Analytics service can only handle 500 TPS
- Who wins? Nobody. Everything crashes.

> **The question:** Should sending an SMS notification be in the critical path of a payment?

---

### The Answer: Decouple with Messaging

```
     User App             UPI Service             Pub/Sub
        |                     |                      |
        |--- POST /transfer ->|                      |
        |                     |--- publish event ---->|
        |                     |      (10ms)           |
        |<--- 200 OK ---------|                      |
        |                     |                      |
        |      Done!          |                      |--- deliver --> Notification
        |      User is        |                      |--- deliver --> Fraud Detection
        |      already gone   |                      |--- deliver --> Analytics
        |                     |                      |
```

- **Response time: ~10ms** (just the publish)
- Notification can be slow — user doesn't care
- Fraud check can take 5 seconds — user doesn't wait
- Analytics DB is down — it catches up later

---

## 2. The Mental Model — The Post Office Analogy

### Think of Pub/Sub Like a Post Office

```
  ┌──────────────────────────────────────────────────────────────────────┐
  │                        THE POST OFFICE (Pub/Sub)                     │
  │                                                                      │
  │   ┌──────────┐      ┌──────────────────┐                            │
  │   │  SENDER  │ ──── │   MAILBOX        │ ──── Copy 1 ──> Recipient A│
  │   │ (Publisher│      │   (Topic)        │                            │
  │   │  = Your  │      │                  │ ──── Copy 2 ──> Recipient B│
  │   │  Service)│      │  "transaction-   │                            │
  │   └──────────┘      │   events"        │ ──── Copy 3 ──> Recipient C│
  │                      └──────────────────┘                            │
  │                                                                      │
  │   LETTER = Message (JSON payload + attributes)                       │
  │   MAILBOX = Topic (named channel)                                    │
  │   RECIPIENT = Subscription (independent consumer)                    │
  │   RECEIPT SIGNATURE = Acknowledgement (ack)                          │
  └──────────────────────────────────────────────────────────────────────┘
```

### Key Insight: The Post Office Makes Copies

- You drop ONE letter in the mailbox
- The post office makes **3 copies** — one for each recipient
- Each recipient picks up their copy **independently**
- Recipient A being on vacation doesn't affect Recipient B
- The post office **holds the letter** until the recipient signs for it (ack)

This is **fan-out** — and it is the core pattern in our UPI system.

---

### Mapping It to Our System

| Post Office | Pub/Sub | Our UPI System |
|-------------|---------|----------------|
| Sender | Publisher | `upi-transfer-service` (port 8080) |
| Letter | Message | `TransactionEvent` JSON + attributes |
| Mailbox | Topic | `transaction-events` |
| Recipient A | Subscription | `notification-sub` → Notification Service (8081) |
| Recipient B | Subscription | `fraud-detection-sub` → Fraud Detection (8082) |
| Recipient C | Subscription | `analytics-sub` → Analytics Service (8083) |
| Signature | Ack | `message.ack()` in each subscriber |
| "Not home" | Nack | `message.nack()` — redeliver later |

---

## 3. Core Concepts Deep Dive

### 3.1 Topics — Named Channels

A topic is a **named resource** that acts as a message bus.

```
Topic name:  projects/{project-id}/topics/transaction-events
Short name:  transaction-events
```

**Key properties:**
- Topics don't store messages — subscriptions do
- A topic with no subscriptions = messages are **dropped** (nobody listening)
- One topic can have many subscriptions (fan-out)
- One publisher can publish to many topics (routing)

**In our code — PubSubSetup.java creates it automatically:**

```java
@Value("${pubsub.topic:transaction-events}")
private String mainTopic;

private void createTopicIfNotExists(PubSubAdmin admin, String topicName) {
    try {
        Topic topic = admin.getTopic(topicName);
        if (topic != null) {
            log.info("  Topic already exists: {}", topicName);
        } else {
            admin.createTopic(topicName);
            log.info("  Created topic: {}", topicName);
        }
    } catch (Exception e) {
        // Fallback: try to create anyway
        admin.createTopic(topicName);
    }
}
```

---

### 3.2 Subscriptions — Independent Consumers

A subscription is an **independent stream** of messages from a topic.

```
Topic: transaction-events
  ├── notification-sub     → Notification Service reads from here
  ├── fraud-detection-sub  → Fraud Detection reads from here
  └── analytics-sub        → Analytics Service reads from here
```

**Critical concept: Each subscription gets its OWN COPY of every message.**

| Message Published | notification-sub | fraud-detection-sub | analytics-sub |
|-------------------|------------------|---------------------|---------------|
| TXN-001 (₹500) | Gets copy | Gets copy | Gets copy |
| TXN-002 (₹7000) | Gets copy | Gets copy | Gets copy |
| TXN-003 (₹200) | Gets copy | Gets copy | Gets copy |

**What if Notification Service is down?**
- `notification-sub` accumulates messages (backlog)
- `fraud-detection-sub` and `analytics-sub` keep processing normally
- When Notification restarts, it processes the backlog
- **No messages lost. No other services affected.**

---

### 3.3 Messages — Data + Attributes

A Pub/Sub message has two parts:

```
┌─────────────────────────────────────────────────┐
│                  Pub/Sub Message                 │
├─────────────────────────────────────────────────┤
│  messageId:    "12345678"    (assigned by Pub/Sub│
│  publishTime:  "2026-04-08T10:15:30Z"           │
│                                                  │
│  DATA (payload — our TransactionEvent JSON):     │
│  {                                               │
│    "transactionId": 42,                          │
│    "senderUpiId": "alice@okaxis",                │
│    "receiverUpiId": "bob@oksbi",                 │
│    "amount": 7500.00,                            │
│    "status": "SUCCESS",                          │
│    "timestamp": "2026-04-08T10:15:29"            │
│  }                                               │
│                                                  │
│  ATTRIBUTES (key-value metadata):                │
│  {                                               │
│    "eventType": "TRANSFER",                      │
│    "status": "SUCCESS",                          │
│    "senderUpiId": "alice@okaxis",                │
│    "receiverUpiId": "bob@oksbi",                 │
│    "amountRange": "HIGH"                         │
│  }                                               │
└─────────────────────────────────────────────────┘
```

**Why separate data from attributes?**
- **Data** = the full payload, parsed by the subscriber
- **Attributes** = lightweight metadata for **filtering** and **routing** without parsing
- Pub/Sub can filter on attributes server-side (tomorrow's topic)

---

### 3.4 The TransactionEvent — Our Message Payload

```java
public class TransactionEvent {
    private Long transactionId;
    private String senderUpiId;
    private String receiverUpiId;
    private BigDecimal amount;
    private String status;         // "SUCCESS" or "FAILED"
    private LocalDateTime timestamp;
}
```

This is the **contract** between publisher and subscribers. All 4 services share this class.

> **Question for you:** What happens if we add a new field to TransactionEvent?
> Do all subscribers break? (Answer: No — Jackson ignores unknown fields by default)

---

### 3.5 Ack/Nack — The Delivery Contract

```
   Pub/Sub                     Subscriber
     |                              |
     |---- deliver message -------->|
     |                              |  (process message)
     |                              |
     |<-------- ack() --------------|  "Got it, don't send again"
     |                              |

               OR

     |---- deliver message -------->|
     |                              |  (processing fails!)
     |<-------- nack() -------------|  "Failed, send it again NOW"
     |                              |
     |---- deliver message -------->|  (redelivered immediately)
```

**Ack deadline:** If the subscriber doesn't ack or nack within the deadline (default 10 seconds), Pub/Sub assumes failure and redelivers.

**In our Notification Service:**

```java
pubSubTemplate.subscribe(subscriptionName, message -> {
    try {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);

        // Process: log the SMS notification
        log.info("  SMS to {}: You sent ₹{} to {}",
                event.getSenderUpiId(), event.getAmount(), event.getReceiverUpiId());

        message.ack();    // SUCCESS — don't redeliver

    } catch (Exception e) {
        log.error("Failed to process: {}", e.getMessage());
        message.nack();   // FAILURE — redeliver immediately
    }
});
```

---

### 3.6 At-Least-Once Delivery — Why Idempotency Matters

**Pub/Sub guarantees: every message is delivered AT LEAST ONCE.**

This means a message might be delivered **twice** (or more). When?

- Subscriber processes message but crashes before calling `ack()`
- Network glitch — ack doesn't reach Pub/Sub
- Pub/Sub rebalances consumers

```
   Pub/Sub                     Subscriber
     |                              |
     |---- deliver TXN-042 -------->|
     |                              |  (processes successfully)
     |                              |  (calls ack())
     |         X--- ack lost! ------|  (network blip)
     |                              |
     |  (no ack received, redeliver)|
     |---- deliver TXN-042 -------->|  (DUPLICATE!)
     |                              |
```

**The fix: Make your processing idempotent.**

| Service | Idempotency Strategy |
|---------|---------------------|
| Notification | Send duplicate SMS? Annoying but not fatal. Could track `transactionId` |
| Fraud Detection | Re-checking same txn? No harm — same result |
| Analytics | Counting same txn twice? Use `transactionId` as dedup key |

> **Rule of thumb:** Always design subscribers as if they might see the same message twice.

---

## 4. Fan-Out Pattern — Our UPI Architecture

### The Architecture We Built

```
                          ┌─────────────────────────────────────────────────────┐
                          │            Google Cloud Pub/Sub                      │
                          │                                                     │
   ┌──────────────┐       │   ┌──────────────────┐                              │
   │              │       │   │                  │   ┌───────────────────┐       │
   │  upi-transfer│  publish  │  transaction-    │   │ notification-sub  │──────>│──> Notification Service
   │  -service    │──────>│   │  events          │──>│                   │       │    (port 8081)
   │              │       │   │  (TOPIC)         │   └───────────────────┘       │    "SMS to alice: ₹500 sent"
   │  port 8080   │       │   │                  │                              │
   │              │       │   │                  │   ┌───────────────────┐       │
   │  POST        │       │   │                  │──>│ fraud-detection-  │──────>│──> Fraud Detection Service
   │  /api/       │       │   │                  │   │ sub               │       │    (port 8082)
   │  transfer    │       │   │                  │   └───────────────────┘       │    "ALERT: ₹7500 > threshold"
   │              │       │   │                  │                              │
   │  @Profile    │       │   │                  │   ┌───────────────────┐       │
   │  ("pubsub")  │       │   │                  │──>│ analytics-sub     │──────>│──> Analytics Service
   │              │       │   │                  │   │                   │       │    (port 8083)
   └──────────────┘       │   └──────────────────┘   └───────────────────┘       │    "Total: ₹45,000 | 12 txns"
                          │                                                     │
                          └─────────────────────────────────────────────────────┘
```

### Why Fan-Out?

**One publish, three independent outcomes:**

| Step | What Happens | Depends On |
|------|-------------|------------|
| 1. Transfer completes | `upi-transfer-service` publishes to topic | Nothing |
| 2. Notification sent | Reads from `notification-sub`, logs SMS | Only Pub/Sub |
| 3. Fraud checked | Reads from `fraud-detection-sub`, applies rules | Only Pub/Sub |
| 4. Analytics recorded | Reads from `analytics-sub`, updates counters | Only Pub/Sub |

**Key insight:** Step 2, 3, and 4 happen **in parallel**. Notification does NOT wait for fraud check. Analytics does NOT wait for notification. They are completely independent.

---

### The Publisher — TransactionEventPublisher.java

```java
@Service
@Profile("pubsub")  // Only active when --spring.profiles.active=pubsub
public class TransactionEventPublisher {

    private final PubSubTemplate pubSubTemplate;
    private final ObjectMapper objectMapper;
    private final String topicName;  // "transaction-events"

    public void publishTransactionEvent(TransactionEvent event) {
        String json = objectMapper.writeValueAsString(event);

        // Message attributes for filtering/routing/monitoring
        Map<String, String> attributes = Map.of(
                "eventType", "TRANSFER",
                "status", event.getStatus(),
                "senderUpiId", event.getSenderUpiId(),
                "receiverUpiId", event.getReceiverUpiId(),
                "amountRange", categorizeAmount(event.getAmount().doubleValue())
        );

        pubSubTemplate.publish(topicName, json, attributes)
                .whenComplete((id, ex) -> {
                    if (ex != null) {
                        log.error("PUBLISH FAILED | txnId={}", event.getTransactionId());
                    } else {
                        log.info("PUBLISHED | messageId={} | txnId={}",
                                id, event.getTransactionId());
                    }
                });
    }

    private String categorizeAmount(double amount) {
        if (amount >= 10000) return "HIGH";
        if (amount >= 1000) return "MEDIUM";
        return "LOW";
    }
}
```

**Notice:**
- `@Profile("pubsub")` — the publisher only activates when the profile is set
- `publish()` returns a `CompletableFuture` — it's async
- Attributes are set at publish time — subscribers can use them for filtering

---

### The Subscriber — NotificationSubscriber.java

```java
@Service
public class NotificationSubscriber {

    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    @PostConstruct
    public void subscribe() {
        pubSubTemplate.subscribe(subscriptionName, message -> {
            long receiveTime = System.currentTimeMillis();
            String payload = message.getPubsubMessage().getData().toStringUtf8();

            try {
                TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);

                // Calculate publish-to-receive latency
                long publishTime = message.getPubsubMessage()
                        .getPublishTime().getSeconds() * 1000;
                long latencyMs = receiveTime - publishTime;
                totalLatencyMs.addAndGet(latencyMs);
                long count = messageCount.incrementAndGet();

                log.info("SMS to {}: You sent ₹{} to {}",
                        event.getSenderUpiId(), event.getAmount(),
                        event.getReceiverUpiId());

                message.ack();   // Done — tell Pub/Sub

            } catch (Exception e) {
                message.nack();  // Failed — redeliver
            }
        });
    }
}
```

---

### The Fraud Detector — Two Rules, Real-Time

```java
@Service
public class FraudDetectionSubscriber {

    private final BigDecimal highAmountThreshold;  // Default: ₹5000
    private final Map<String, List<LocalDateTime>> recentTransfers = new ConcurrentHashMap<>();

    // Inside subscribe() callback:

    // ---- Rule 1: High-value transaction ----
    boolean isHighAmount = event.getAmount().compareTo(highAmountThreshold) >= 0;
    if (isHighAmount) {
        alertCount.incrementAndGet();
        log.warn("ALERT: HIGH VALUE TRANSACTION! ₹{} exceeds ₹{}",
                event.getAmount(), highAmountThreshold);
    }

    // ---- Rule 2: Rapid-fire transfers ----
    List<LocalDateTime> transfers = recentTransfers
            .computeIfAbsent(sender, k -> new CopyOnWriteArrayList<>());
    transfers.add(LocalDateTime.now());

    // Clean entries older than 60 seconds
    transfers.removeIf(t -> t.isBefore(LocalDateTime.now().minusSeconds(60)));

    if (transfers.size() > 3) {
        alertCount.incrementAndGet();
        log.warn("ALERT: RAPID TRANSFERS! {} made {} transfers in 60s",
                sender, transfers.size());
    }
```

**Two rules running in real-time on every transaction:**
1. Amount > ₹5,000 → flag as HIGH VALUE
2. More than 3 transfers in 60 seconds from same sender → flag as RAPID-FIRE

---

### The Analytics Engine — Real-Time Aggregation

```java
@Service
public class AnalyticsSubscriber {

    private volatile BigDecimal totalAmount = BigDecimal.ZERO;
    private volatile BigDecimal maxTransaction = BigDecimal.ZERO;
    private volatile BigDecimal minTransaction = BigDecimal.valueOf(Long.MAX_VALUE);
    private final Map<String, AtomicLong> topSenders = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> amountBuckets = new ConcurrentHashMap<>(Map.of(
            "LOW (<1000)", new AtomicLong(0),
            "MEDIUM (1000-5000)", new AtomicLong(0),
            "HIGH (>5000)", new AtomicLong(0)
    ));

    // Inside subscribe() callback:
    totalAmount = totalAmount.add(event.getAmount());
    topSenders.computeIfAbsent(event.getSenderUpiId(),
            k -> new AtomicLong(0)).incrementAndGet();

    // Bucket by amount range
    if (amt >= 5000) amountBuckets.get("HIGH (>5000)").incrementAndGet();
    else if (amt >= 1000) amountBuckets.get("MEDIUM (1000-5000)").incrementAndGet();
    else amountBuckets.get("LOW (<1000)").incrementAndGet();
```

**Tracks in real-time:** total volume, running average, min/max, top senders/receivers, amount distribution.

---

## 5. Message Attributes — Metadata That Matters

### What Are Attributes?

Attributes are **key-value string pairs** attached to a message, **separate from the payload**.

```java
Map<String, String> attributes = Map.of(
        "eventType", "TRANSFER",
        "status", event.getStatus(),              // "SUCCESS" or "FAILED"
        "senderUpiId", event.getSenderUpiId(),     // "alice@okaxis"
        "receiverUpiId", event.getReceiverUpiId(), // "bob@oksbi"
        "amountRange", categorizeAmount(amount)     // "LOW", "MEDIUM", "HIGH"
);

pubSubTemplate.publish(topicName, json, attributes);
```

### Why Not Just Put Everything in the JSON?

| Concern | Data (JSON payload) | Attributes |
|---------|-------------------|------------|
| Size | Up to 10 MB | Key + value < 1 MB total |
| Type | Binary / string | String only |
| Filtering | Must deserialize to filter | **Server-side filtering** |
| Access cost | Parse JSON first | Direct access, no parsing |
| Use case | Full event details | Routing, filtering, monitoring |

### Our Amount Categorization

```java
private String categorizeAmount(double amount) {
    if (amount >= 10000) return "HIGH";     // ₹10,000+
    if (amount >= 1000)  return "MEDIUM";   // ₹1,000 - ₹9,999
    return "LOW";                            // Under ₹1,000
}
```

**Tomorrow's preview:** You can create a subscription filter like:
```
attributes.amountRange = "HIGH"
```
And that subscription will ONLY receive high-value transactions. The filtering happens **server-side** — the subscriber never even sees low-value messages.

---

### Attributes in the Fraud Detector

The fraud detection subscriber reads attributes directly:

```java
Map<String, String> attributes = message.getPubsubMessage().getAttributesMap();
log.info("Attributes: {}", attributes);
// Output: {eventType=TRANSFER, status=SUCCESS, senderUpiId=alice@okaxis,
//          receiverUpiId=bob@oksbi, amountRange=HIGH}
```

**Quick routing decision without parsing the full JSON:**
- `amountRange = "HIGH"` → run extra fraud rules
- `status = "FAILED"` → skip fraud check (transfer didn't go through)
- `eventType` → future-proof for different event types

---

## 6. Pull vs Push Subscriptions

### Two Ways to Receive Messages

```
  PULL (our services use this)              PUSH

  Subscriber          Pub/Sub             Pub/Sub          Subscriber
      |                  |                   |                  |
      |--- "any msgs?" ->|                   |--- POST /endpoint ->|
      |<-- here's 10 ----|                   |<-- 200 OK ----------|
      |                  |                   |                  |
      |  (process them)  |                   |  (process it)    |
      |                  |                   |                  |
      |--- ack 10 ------>|                   |                  |
      |                  |                   |--- POST /endpoint ->|
      |--- "any msgs?" ->|                   |<-- 200 OK ----------|
```

### Comparison

| Feature | Pull | Push |
|---------|------|------|
| Direction | Subscriber asks for messages | Pub/Sub sends to endpoint |
| Endpoint needed? | No — subscriber connects out | Yes — needs HTTPS URL |
| Best for | Long-running services, batch | Serverless (Cloud Run, Functions) |
| Flow control | Subscriber controls rate | Pub/Sub controls rate |
| Scaling | Subscriber manages threads | Auto-scales with HTTP |
| Firewall friendly? | Yes — outbound only | No — needs inbound HTTPS |
| Our services | **All 3 use Pull** | Not used today |

### Why We Use Pull

Our Spring Boot services are **long-running JVM processes**. They:
1. Start up, subscribe via `@PostConstruct`
2. Messages flow in continuously via the gRPC streaming pull
3. Process in the callback, ack/nack immediately
4. Track metrics (count, latency) in-memory

**When would you use Push?**
- Cloud Run / Cloud Functions (serverless, scale-to-zero)
- Webhook-style integrations
- Services behind a load balancer with a stable URL

---

## 7. The Emulator — Local Dev Without GCP

### Why the Emulator Exists

```
  Without emulator:                      With emulator:

  Your laptop ──── internet ──── GCP     Your laptop ──── localhost:8085
                                         (emulator runs locally)
  Problems:
  - Need GCP project                     Benefits:
  - Need credentials                     - No GCP project needed
  - Need internet                        - No credentials needed
  - Costs money                          - Works offline
  - Shared state with team               - Free
                                         - Isolated per developer
```

### Starting the Emulator

```bash
# Start the emulator (runs on port 8085 by default)
gcloud beta emulators pubsub start --project=test-project

# In another terminal, set the environment variable
export PUBSUB_EMULATOR_HOST=localhost:8085
```

### Spring Boot Configuration

In `application.properties` (or `application-pubsub.properties`):

```properties
spring.cloud.gcp.pubsub.emulator-host=localhost:8085
spring.cloud.gcp.project-id=test-project
```

When `emulator-host` is set, Spring Cloud GCP **skips all authentication** and connects to the local emulator instead.

---

### PubSubSetup.java — Auto-Creating Resources

In real GCP, you create topics and subscriptions via Console or `gcloud`. The emulator starts **empty** — no topics, no subscriptions.

Our `PubSubSetup.java` solves this:

```java
@Configuration
@Profile("pubsub")
public class PubSubSetup {

    @Bean
    CommandLineRunner setupPubSubResources(PubSubAdmin pubSubAdmin) {
        return args -> {
            // Create main topic
            createTopicIfNotExists(pubSubAdmin, "transaction-events");

            // Create DLQ topic
            createTopicIfNotExists(pubSubAdmin, "transaction-events-dlq");

            // Create fan-out subscriptions (all listen to same topic!)
            createSubscriptionIfNotExists(pubSubAdmin, "notification-sub", "transaction-events");
            createSubscriptionIfNotExists(pubSubAdmin, "fraud-detection-sub", "transaction-events");
            createSubscriptionIfNotExists(pubSubAdmin, "analytics-sub", "transaction-events");

            // Create DLQ subscription
            createSubscriptionIfNotExists(pubSubAdmin, "dlq-monitor-sub", "transaction-events-dlq");
        };
    }
}
```

**The flow on startup:**
1. Start emulator → empty
2. Start `upi-transfer-service` with `--spring.profiles.active=pubsub` → creates topic + all subscriptions
3. Start subscriber services → they subscribe to already-created subscriptions
4. Transfer money → messages flow through

> **Important:** Start `upi-transfer-service` FIRST — it creates the infrastructure.

---

## 8. The Four Factors

### Throughput, Latency, Durability, Availability

These are the four things you need to understand about ANY messaging system. Let's measure them in our system.

---

### 8.1 Throughput — Messages Per Second

**What is it?** The number of messages the system can process per unit of time.

**Google Cloud Pub/Sub throughput:**
- Publisher: **millions of messages/second** per topic
- Subscriber: scales with number of subscribers
- No pre-provisioning needed — auto-scales

**Our system's throughput — visible via /stats:**

```bash
# After sending 50 transfers over 30 seconds:
curl http://localhost:8081/stats
{
    "service": "notification-service",
    "messagesProcessed": 50,
    "uptimeSeconds": 30,
    "throughputPerSecond": 1.67
}
```

**How to think about throughput:**

| Scale | Messages/sec | Example |
|-------|-------------|---------|
| Dev/Testing | 1-10 | Our local demo |
| Small production | 100-1,000 | Regional bank |
| Medium production | 1,000-10,000 | National payment app |
| UPI-scale | 100,000+ | NPCI infrastructure |
| Pub/Sub limit | Millions | Google-scale |

**What limits throughput in our system?**
- Not Pub/Sub — it handles millions
- Our subscriber processing speed
- Database writes (if we had them)
- Network between services and Pub/Sub

---

### 8.2 Latency — Publish to Receive Time

**What is it?** The time between publishing a message and the subscriber receiving it.

**How we measure it in our services:**

```java
// In every subscriber:
long receiveTime = System.currentTimeMillis();
long publishTime = message.getPubsubMessage().getPublishTime().getSeconds() * 1000;
long latencyMs = receiveTime - publishTime;
```

**Typical latency numbers:**

| Environment | Latency |
|-------------|---------|
| Emulator (local) | 5-50ms |
| Same-region GCP | 50-100ms |
| Cross-region GCP | 100-300ms |
| Our /stats shows | `avgLatencyMs` field |

**Factors that affect latency:**

```
  Publisher ──── network ──── Pub/Sub ──── network ──── Subscriber
     |              |            |             |            |
  serialize     RTT to       message       RTT to      deserialize
  to JSON      Pub/Sub      routing       subscriber    + process
  (~1ms)       (5-50ms)     (~5ms)       (5-50ms)       (varies)
```

1. **Network distance** — emulator is fastest (localhost)
2. **Message size** — larger messages = more network time
3. **Subscriber processing time** — slow processing delays the ack
4. **Batching** — Pub/Sub batches messages for efficiency (slight delay)
5. **Backlog** — if subscriber falls behind, newer messages wait

---

### 8.3 Durability — Messages Survive Failures

**What is it?** The guarantee that published messages will not be lost.

**Pub/Sub durability guarantees:**
- Messages are **replicated across multiple zones** within a region
- Messages persist until **acknowledged** (or retention expires)
- Default retention: **7 days** (configurable up to 31 days)
- Even if ALL subscribers are down, messages wait

**The demo scenario — kill a subscriber:**

```
  Timeline:

  T=0:   All 3 subscribers running, processing messages

  T=10:  Kill notification-service (Ctrl+C)
         fraud-detection and analytics keep processing

  T=20:  Send 5 more transfers
         fraud-detection: processes all 5 ✓
         analytics: processes all 5 ✓
         notification-sub: messages pile up (backlog = 5)

  T=30:  Send 5 more transfers
         notification-sub: backlog = 10

  T=40:  Restart notification-service
         Immediately processes all 10 backlogged messages!
         No messages lost. Order preserved (within partition).
```

> **This is the power of durable messaging.** The post office holds your mail while you're on vacation.

**What about the publisher?** If `pubSubTemplate.publish()` succeeds (returns a message ID), the message is **durably stored**. Even if Pub/Sub has an internal failure right after, the message is already replicated.

---

### 8.4 Availability — The System Stays Up

**What is it?** The percentage of time the messaging system is operational.

**Pub/Sub SLA: 99.95% availability**

| SLA | Downtime per year | Downtime per month |
|-----|-------------------|-------------------|
| 99.9% | 8h 45m | 43m |
| 99.95% | 4h 22m | 21m |
| 99.99% | 52m | 4m |

**What happens when things fail?**

| Failure | Impact | Recovery |
|---------|--------|----------|
| One subscriber dies | Other subscribers unaffected, messages backlog | Restart subscriber, process backlog |
| All subscribers die | Messages persist in Pub/Sub (up to 7 days) | Restart any subscriber, process backlog |
| Publisher dies | No new messages published, subscribers idle | Restart publisher |
| Pub/Sub itself | Extremely rare (99.95% SLA), publish fails | Retry with exponential backoff |
| Network partition | Publish/subscribe temporarily fail | Auto-reconnect when network recovers |

**Regional vs Global:**
- Pub/Sub is **regional by default** — messages stay in one region
- For global availability: use **global endpoints** or publish to multiple regions
- For our UPI service: single-region is fine (data locality requirements)

---

### The Four Factors — Summary Table

| Factor | What We Measure | How We Measure It | Pub/Sub Guarantee |
|--------|----------------|-------------------|-------------------|
| **Throughput** | Messages/sec processed | `/stats` → `throughputPerSecond` | Millions/sec |
| **Latency** | Publish → receive time | `/stats` → `avgLatencyMs` | ~100ms typical |
| **Durability** | Messages survive failures | Kill & restart subscriber | 7-day retention, replicated |
| **Availability** | System uptime | SLA monitoring | 99.95% |

---

## 9. Live Demo — End-to-End Message Flow

### Setup — 5 Terminal Windows

```
  ┌────────────────────────────────────────────────────────────────────────┐
  │ Terminal 1: Pub/Sub Emulator                                          │
  │ $ gcloud beta emulators pubsub start --project=test-project           │
  ├────────────────────────────────────────────────────────────────────────┤
  │ Terminal 2: UPI Transfer Service (Publisher)                           │
  │ $ export PUBSUB_EMULATOR_HOST=localhost:8085                          │
  │ $ cd upi-transfer-service                                             │
  │ $ mvn spring-boot:run -Dspring-boot.run.profiles=pubsub               │
  │   (port 8080 — creates topics & subscriptions on startup)             │
  ├────────────────────────────────────────────────────────────────────────┤
  │ Terminal 3: Notification Service                                       │
  │ $ export PUBSUB_EMULATOR_HOST=localhost:8085                          │
  │ $ cd notification-service                                              │
  │ $ mvn spring-boot:run         (port 8081)                              │
  ├────────────────────────────────────────────────────────────────────────┤
  │ Terminal 4: Fraud Detection Service                                    │
  │ $ export PUBSUB_EMULATOR_HOST=localhost:8085                          │
  │ $ cd fraud-detection-service                                           │
  │ $ mvn spring-boot:run         (port 8082)                              │
  ├────────────────────────────────────────────────────────────────────────┤
  │ Terminal 5: Analytics Service                                          │
  │ $ export PUBSUB_EMULATOR_HOST=localhost:8085                          │
  │ $ cd analytics-service                                                 │
  │ $ mvn spring-boot:run         (port 8083)                              │
  └────────────────────────────────────────────────────────────────────────┘
```

> **Important:** Set `PUBSUB_EMULATOR_HOST` in EVERY terminal. Start Terminal 2 first (creates infrastructure).

---

### Demo Step 1 — Send a Transfer

```bash
curl -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "alice@okaxis",
    "receiverUpiId": "bob@oksbi",
    "amount": 7500
  }'
```

**Watch all 3 subscriber terminals simultaneously!**

---

### Demo Step 2 — What You'll See

**Terminal 2 (Publisher) logs:**
```
PUBLISHED | topic=transaction-events | txnId=1 | messageId=1
| from=alice@okaxis | to=bob@oksbi | amount=7500
| attributes={eventType=TRANSFER, status=SUCCESS, amountRange=HIGH}
```

**Terminal 3 (Notification) logs:**
```
NOTIFICATION SENT!
SMS to alice@okaxis: You sent ₹7500 to bob@oksbi
SMS to bob@oksbi: You received ₹7500 from alice@okaxis
TxnId: 1 | Latency: 12ms
```

**Terminal 4 (Fraud Detection) logs:**
```
FRAUD CHECK | TxnId: 1 | alice@okaxis → bob@oksbi | ₹7500
Attributes: {eventType=TRANSFER, status=SUCCESS, amountRange=HIGH}
ALERT: HIGH VALUE TRANSACTION! ₹7500 exceeds threshold ₹5000
Action: Flagged for manual review
```

**Terminal 5 (Analytics) logs:**
```
ANALYTICS RECORDED | TxnId: 1
alice@okaxis → bob@oksbi | ₹7500
Running totals:
  Transactions: 1 | Total volume: ₹7500
  Avg: ₹7500 | Min: ₹7500 | Max: ₹7500
```

---

### Demo Step 3 — Check Stats on All Services

```bash
# Notification stats
curl -s http://localhost:8081/stats | python3 -m json.tool
{
    "service": "notification-service",
    "messagesProcessed": 1,
    "avgLatencyMs": 12,
    "uptimeSeconds": 45,
    "throughputPerSecond": 0.02
}

# Fraud detection stats
curl -s http://localhost:8082/stats | python3 -m json.tool

# Analytics stats (has extra fields)
curl -s http://localhost:8083/stats | python3 -m json.tool
```

---

### Demo Step 4 — Durability Test

**Kill the notification service (Ctrl+C in Terminal 3)**

```bash
# Send 3 more transfers while notification is down
curl -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"charlie@okhdfc","receiverUpiId":"dave@oksbi","amount":200}'

curl -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"eve@okaxis","receiverUpiId":"frank@oksbi","amount":15000}'

curl -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"grace@okhdfc","receiverUpiId":"heidi@oksbi","amount":3000}'
```

**Observe:**
- Fraud Detection terminal: processes all 3 (including ALERT for ₹15,000)
- Analytics terminal: processes all 3 (total volume growing)
- Notification terminal: DEAD — nothing happens

**Now restart notification service:**
```bash
cd notification-service && mvn spring-boot:run
```

**Watch Terminal 3 — it immediately processes all 3 backlogged messages!**

> This is durability in action. The post office held the mail.

---

### Demo Step 5 — Rapid-Fire Fraud Detection

```bash
# Send 5 transfers from the same sender in quick succession
for i in {1..5}; do
  curl -s -X POST http://localhost:8080/api/transfer \
    -H "Content-Type: application/json" \
    -d '{"senderUpiId":"suspicious@okaxis","receiverUpiId":"target@oksbi","amount":100}'
done
```

**Watch Fraud Detection terminal:**
```
FRAUD CHECK | suspicious@okaxis → target@oksbi | ₹100
Result: CLEAN — no fraud indicators

FRAUD CHECK | suspicious@okaxis → target@oksbi | ₹100
Result: CLEAN — no fraud indicators

FRAUD CHECK | suspicious@okaxis → target@oksbi | ₹100
Result: CLEAN — no fraud indicators

FRAUD CHECK | suspicious@okaxis → target@oksbi | ₹100
ALERT: RAPID TRANSFERS! suspicious@okaxis made 4 transfers in 60s window
Action: Account temporarily flagged
```

---

## 10. What's Coming Tomorrow — Day 38

### Advanced Pub/Sub Patterns

| Topic | What You'll Learn |
|-------|------------------|
| **Dead Letter Queues (DLQ)** | What happens when a message fails repeatedly? Route it to a DLQ topic. |
| **Message Ordering** | Guaranteeing order with ordering keys. When you need it, when you don't. |
| **Subscription Filters** | Server-side filtering: `attributes.amountRange = "HIGH"` — only receive what you need. |
| **Exactly-Once Delivery** | Moving from at-least-once to exactly-once. The trade-offs. |
| **Deploy to Real GCP** | Move from emulator to actual Google Cloud Pub/Sub. Create resources, set IAM, deploy. |
| **Retry Policies** | Exponential backoff, max retries, DLQ integration. |

### The Big Picture

```
  Day 36: CI/CD, Monitoring basics
  Day 37: Pub/Sub foundations, fan-out, emulator    ← YOU ARE HERE
  Day 38: DLQ, ordering, filtering, deploy to GCP
  Day 39: Cloud Run + Pub/Sub integration
  Day 40: Final project — full microservices on GCP
```

---

## Quick Reference — Commands Cheat Sheet

```bash
# Start emulator
gcloud beta emulators pubsub start --project=test-project

# Set emulator host (EVERY terminal)
export PUBSUB_EMULATOR_HOST=localhost:8085

# Start publisher (must be first!)
cd upi-transfer-service
mvn spring-boot:run -Dspring-boot.run.profiles=pubsub

# Start subscribers
cd notification-service   && mvn spring-boot:run   # port 8081
cd fraud-detection-service && mvn spring-boot:run   # port 8082
cd analytics-service       && mvn spring-boot:run   # port 8083

# Send a transfer
curl -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"alice@okaxis","receiverUpiId":"bob@oksbi","amount":5000}'

# Check stats
curl http://localhost:8081/stats   # Notification
curl http://localhost:8082/stats   # Fraud Detection
curl http://localhost:8083/stats   # Analytics
```

---

## Key Takeaways

1. **Synchronous REST between microservices is a trap** — latency stacks, coupling tightens, failures cascade

2. **Pub/Sub decouples services** — publisher fires and forgets, subscribers process independently

3. **Fan-out is the core pattern** — one publish, three independent outcomes

4. **Messages = Data + Attributes** — attributes enable filtering without parsing

5. **At-least-once means design for duplicates** — idempotency is not optional

6. **Durability means messages wait** — kill a subscriber, restart later, nothing lost

7. **The emulator is your best friend** — local dev without GCP credentials or costs

8. **Measure everything** — throughput, latency, durability, availability via /stats endpoints

---

## Discussion Questions

1. In the synchronous model, if the fraud detection service takes 2 seconds, what's the user experience? In the Pub/Sub model?

2. We have 3 subscribers. What if we add a 4th (say, a rewards-service)? What changes in the publisher code? (Answer: Nothing!)

3. What happens if the emulator crashes while messages are in-flight? (Answer: Messages are lost — the emulator is NOT durable. Real Pub/Sub IS.)

4. Why does our fraud detection service track `recentTransfers` in a `ConcurrentHashMap` instead of a database? (Answer: Speed — in-memory is microseconds, DB is milliseconds. Trade-off: state lost on restart.)

5. If a message is delivered twice to the analytics service, our total transaction count will be wrong. How would you fix this?

---

*Ford Phase 6 — Day 37 | Trainer: Nag | Pub/Sub Foundations with Microservices*
