# GCP Pub/Sub for Java/Spring Boot Developers
## Asynchronous Messaging for the UPI Transfer Service

---

## 1. The Problem — Synchronous Calls Don't Scale

```
 User App          UPI Service         Bank API        Notification       Audit Service
    |                  |                  |                |                   |
    |--- POST /pay --->|                  |                |                   |
    |                  |--- debit ------->|                |                   |
    |                  |<-- 200 OK -------|                |                   |
    |                  |--- credit ------->|               |                   |
    |                  |<-- 200 OK --------|               |                   |
    |                  |--- send SMS ---------------------->|                  |
    |                  |<-- 200 OK -------------------------|                  |
    |                  |--- log audit ---------------------------------------->|
    |                  |<-- 200 OK --------------------------------------------|
    |<-- 200 OK -------|                  |                |                   |
    |                  |                  |                |                   |
         Total latency = sum of ALL downstream calls (~2-4 seconds)
```

### What goes wrong?

- **Latency stacks up** — each call adds to the response time
- **Tight coupling** — if Notification is down, the whole payment fails
- **No retry isolation** — a transient Audit failure blocks the user
- **Scaling mismatch** — Bank API handles 100 TPS, but we get 10,000 TPS bursts

> "If your payment succeeds but your SMS gateway is slow, should the user wait?"

---

## 2. What is Pub/Sub?

Google Cloud Pub/Sub is a **fully managed, serverless** messaging service for event-driven architectures.

| Property                  | Details                                        |
|---------------------------|------------------------------------------------|
| **Type**                  | Managed message broker (topic-based)           |
| **Delivery**              | At-least-once (exactly-once available)         |
| **Ordering**              | Optional, per ordering key                     |
| **Retention**             | Default 7 days, max 31 days                    |
| **Max message size**      | 10 MB                                          |
| **Throughput**            | Millions of messages/sec                       |
| **Latency**               | ~100ms typical end-to-end                      |
| **Global availability**   | Multi-region by default                        |
| **Pricing**               | Per data volume ($40/TiB ingested + delivered) |
| **Dead Letter support**   | Built-in                                       |
| **Filtering**             | Server-side attribute filters                  |
| **Schema enforcement**    | Avro and Protocol Buffers                      |
| **Exactly-once delivery** | Supported on pull subscriptions                |

---

## 3. Core Concepts

### Topic
- A **named channel** to which publishers send messages
- Example: `projects/ford-upi/topics/payment-events`

### Subscription
- A **named resource** representing a stream of messages from a topic
- Each subscription receives a **copy** of every message published to its topic
- Types: **Pull** and **Push**

### Message
- The unit of data — contains:
  - `data` — Base64-encoded payload (up to 10 MB)
  - `attributes` — Key-value string pairs (metadata)
  - `messageId` — Unique ID assigned by Pub/Sub
  - `publishTime` — Timestamp when Pub/Sub received the message
  - `orderingKey` — Optional, for ordered delivery

### Ack / Nack
- **Ack (Acknowledge)** — tells Pub/Sub the message was processed; don't redeliver
- **Nack (Negative Acknowledge)** — tells Pub/Sub to redeliver immediately
- **Ack Deadline** — time limit to ack before Pub/Sub assumes failure (default 10s, max 600s)

---

## 4. How Pub/Sub Works — Fan-Out Pattern

```
                          +------------------+
                          |   payment-events |  <--- TOPIC
                          |      (Topic)     |
                          +--------+---------+
                                   |
                   +-----------+---+-----------+-----------+
                   |           |               |           |
                   v           v               v           v
            +-----------+ +-----------+  +-----------+ +-----------+
            | notif-sub | | audit-sub |  | fraud-sub | | analytics |
            |  (Pull)   | |  (Pull)   |  |  (Push)   | |  (Pull)   |
            +-----------+ +-----------+  +-----------+ +-----------+
                   |           |               |           |
                   v           v               v           v
            +-----------+ +-----------+  +-----------+ +-----------+
            | Notif Svc | | Audit Svc |  | Fraud Svc | | BigQuery  |
            +-----------+ +-----------+  +-----------+ +-----------+

   ONE publish  --->  FOUR independent subscribers get their own copy
```

### Key points:
- Publisher knows **nothing** about subscribers
- Each subscription maintains its **own** cursor (ack state)
- Subscribers can be added/removed without changing the publisher
- Messages are retained until **every** subscription acknowledges

---

## 5. Pull vs Push Subscriptions

| Dimension              | Pull                                    | Push                                   |
|------------------------|-----------------------------------------|----------------------------------------|
| **How it works**       | Subscriber polls Pub/Sub for messages   | Pub/Sub sends HTTP POST to endpoint    |
| **Endpoint needed?**   | No                                      | Yes (HTTPS with valid cert)            |
| **Flow control**       | Subscriber controls rate                | Pub/Sub controls rate (backoff)        |
| **Authentication**     | Subscriber authenticates to Pub/Sub     | Pub/Sub authenticates to subscriber    |
| **Latency**            | Slightly higher (polling interval)      | Lower (immediate push)                 |
| **Exactly-once**       | Supported                               | Not supported                          |
| **Best for**           | GKE, Compute Engine, batch processing   | Cloud Run, Cloud Functions, App Engine |
| **Scaling**            | Scale subscribers yourself               | Auto-scales with Cloud Run             |
| **Offline tolerance**  | Messages wait; resume when ready        | Must be reachable or messages retry    |
| **Firewall friendly?** | Yes (outbound only)                     | No (inbound HTTPS required)            |

### When to use which?

- **Pull** — when you need flow control, exactly-once, or your subscriber runs on GKE/VMs
- **Push** — when you use serverless (Cloud Run/Functions) and want auto-scaling

---

## 6. Pub/Sub vs Kafka vs RabbitMQ — Deep Comparison

| Dimension                | GCP Pub/Sub               | Apache Kafka              | RabbitMQ                  |
|--------------------------|---------------------------|---------------------------|---------------------------|
| **Managed by**           | Google (fully managed)    | Self-managed or Confluent | Self-managed or CloudAMQP |
| **Protocol**             | gRPC / REST               | Kafka protocol (binary)   | AMQP 0-9-1               |
| **Ordering**             | Per ordering key          | Per partition             | Per queue                 |
| **Retention**            | Up to 31 days             | Unlimited (log-based)    | Until consumed            |
| **Replay**               | Seek to time / snapshot   | Consumer offset reset    | Not natively supported    |
| **Throughput**           | Millions/sec (managed)    | Millions/sec (tuned)     | Tens of thousands/sec     |
| **Delivery guarantee**   | At-least-once / exactly-once | At-least-once / exactly-once | At-least-once / at-most-once |
| **Dead letter support**  | Built-in                  | Manual (separate topic)  | Built-in (DLX)           |
| **Server-side filter**   | Yes (attribute filters)   | No (consumer-side)       | Routing keys / headers    |
| **Ops burden**           | Zero (serverless)         | High (ZooKeeper, brokers)| Medium (Erlang, clusters) |
| **Cost model**           | Pay per data volume       | Infra cost (VMs, disks)  | Infra cost                |
| **Best for**             | GCP-native event-driven   | High-throughput streaming | Complex routing patterns  |

> **For Ford UPI Service:** Pub/Sub wins because it's zero-ops, GCP-native, and our throughput is well within its capabilities.

---

## 7. UPI Service Use Case — Before and After

### BEFORE (Synchronous)

```
  POST /api/v1/transfer
        |
        v
  +-- UPI Service --+
  |  1. Validate    |
  |  2. Debit Bank  |  <-- 500ms
  |  3. Credit Bank |  <-- 500ms
  |  4. Send SMS    |  <-- 300ms   (BLOCKS if SMS gateway is slow!)
  |  5. Log Audit   |  <-- 200ms   (BLOCKS if Audit DB is down!)
  |  6. Fraud Check |  <-- 400ms   (BLOCKS if ML model is loading!)
  +-----------------+
  Total: ~1900ms   User waits for EVERYTHING
```

### AFTER (Async with Pub/Sub)

```
  POST /api/v1/transfer
        |
        v
  +-- UPI Service --+       +---[ payment-events TOPIC ]---+
  |  1. Validate    |       |                               |
  |  2. Debit Bank  |       |   +-- notif-sub ---------->  SMS Service
  |  3. Credit Bank |       |   |
  |  4. Publish ----|------>|   +-- audit-sub ---------->  Audit Service
  +-----------------+       |   |
  Total: ~1050ms            |   +-- fraud-sub ---------->  Fraud Service
  User gets response!       |   |
                            |   +-- analytics-sub ------>  BigQuery
                            +-------------------------------+
```

- User latency reduced from **1900ms to 1050ms**
- SMS, Audit, Fraud failures **don't affect the payment response**
- Each downstream service **scales independently**

---

## 8. gcloud Commands — Topics

### Create a topic

```bash
gcloud pubsub topics create payment-events \
  --project=ford-upi-prod

gcloud pubsub topics create payment-notifications \
  --project=ford-upi-prod

gcloud pubsub topics create payment-deadletter \
  --project=ford-upi-prod \
  --message-retention-duration=7d
```

### List all topics

```bash
gcloud pubsub topics list --project=ford-upi-prod
```

### Describe a topic

```bash
gcloud pubsub topics describe payment-events \
  --project=ford-upi-prod
```

### Update topic labels

```bash
gcloud pubsub topics update payment-events \
  --project=ford-upi-prod \
  --update-labels=env=prod,team=upi,cost-center=payments
```

### Set message retention on a topic

```bash
gcloud pubsub topics update payment-events \
  --project=ford-upi-prod \
  --message-retention-duration=3d
```

### Delete a topic

```bash
# WARNING: This does not delete subscriptions — they become orphaned
gcloud pubsub topics delete payment-events \
  --project=ford-upi-prod
```

---

## 9. gcloud Commands — Subscriptions

### Create a pull subscription

```bash
gcloud pubsub subscriptions create notif-sub \
  --topic=payment-events \
  --project=ford-upi-prod \
  --ack-deadline=30 \
  --message-retention-duration=7d \
  --expiration-period=never
```

### Create a push subscription

```bash
gcloud pubsub subscriptions create fraud-push-sub \
  --topic=payment-events \
  --project=ford-upi-prod \
  --push-endpoint=https://fraud-service-abc123.a.run.app/pubsub/push \
  --push-auth-service-account=pubsub-push-sa@ford-upi-prod.iam.gserviceaccount.com
```

### Set ack deadline

```bash
gcloud pubsub subscriptions update notif-sub \
  --project=ford-upi-prod \
  --ack-deadline=60
```

### Set message retention

```bash
gcloud pubsub subscriptions update notif-sub \
  --project=ford-upi-prod \
  --message-retention-duration=3d \
  --retain-acked-messages
```

### Create subscription with a filter

```bash
gcloud pubsub subscriptions create high-value-sub \
  --topic=payment-events \
  --project=ford-upi-prod \
  --message-filter='attributes.amount_tier = "high"'
```

### List all subscriptions

```bash
gcloud pubsub subscriptions list --project=ford-upi-prod
```

### Describe a subscription

```bash
gcloud pubsub subscriptions describe notif-sub \
  --project=ford-upi-prod
```

### Delete a subscription

```bash
gcloud pubsub subscriptions delete notif-sub \
  --project=ford-upi-prod
```

---

## 10. gcloud Commands — Publishing Messages

### Simple publish

```bash
gcloud pubsub topics publish payment-events \
  --project=ford-upi-prod \
  --message='{"txnId":"TXN001","amount":5000,"status":"SUCCESS"}'
```

### Publish with attributes

```bash
gcloud pubsub topics publish payment-events \
  --project=ford-upi-prod \
  --message='{"txnId":"TXN002","amount":50000,"status":"SUCCESS"}' \
  --attribute=event_type=PAYMENT_COMPLETED,amount_tier=high,source=upi-service
```

### Publish in a loop (load testing)

```bash
for i in $(seq 1 100); do
  gcloud pubsub topics publish payment-events \
    --project=ford-upi-prod \
    --message="{\"txnId\":\"TXN$(printf '%04d' $i)\",\"amount\":$((RANDOM % 10000)),\"status\":\"SUCCESS\"}" \
    --attribute=event_type=PAYMENT_COMPLETED,batch_id=load-test-001
done
```

### Publish with ordering key

```bash
# Messages with the same ordering key are delivered in order
gcloud pubsub topics publish payment-events \
  --project=ford-upi-prod \
  --message='{"txnId":"TXN003","step":"DEBIT","status":"SUCCESS"}' \
  --ordering-key=user-12345 \
  --attribute=event_type=PAYMENT_STEP

gcloud pubsub topics publish payment-events \
  --project=ford-upi-prod \
  --message='{"txnId":"TXN003","step":"CREDIT","status":"SUCCESS"}' \
  --ordering-key=user-12345 \
  --attribute=event_type=PAYMENT_STEP
```

---

## 11. gcloud Commands — Pulling Messages

### Pull without acknowledging (peek)

```bash
gcloud pubsub subscriptions pull notif-sub \
  --project=ford-upi-prod \
  --limit=5 \
  --auto-ack=false
```

### Pull with auto-ack

```bash
gcloud pubsub subscriptions pull notif-sub \
  --project=ford-upi-prod \
  --limit=10 \
  --auto-ack
```

### Pull in JSON format (for scripting)

```bash
gcloud pubsub subscriptions pull notif-sub \
  --project=ford-upi-prod \
  --limit=5 \
  --format=json \
  --auto-ack=false
```

### Manual acknowledge by ack ID

```bash
# Step 1: Pull and capture the ack ID
ACK_ID=$(gcloud pubsub subscriptions pull notif-sub \
  --project=ford-upi-prod \
  --limit=1 \
  --format='value(ackId)')

# Step 2: Acknowledge the message
gcloud pubsub subscriptions ack notif-sub \
  --project=ford-upi-prod \
  --ack-ids="$ACK_ID"
```

### Modify ack deadline (extend processing time)

```bash
gcloud pubsub subscriptions modify-ack-deadline notif-sub \
  --project=ford-upi-prod \
  --ack-ids="$ACK_ID" \
  --ack-deadline=120
```

---

## 12. Message Filtering

Server-side filtering lets Pub/Sub **discard messages before delivery**, saving bandwidth and processing.

### Filter syntax examples

| Filter Expression                              | Meaning                                    |
|------------------------------------------------|--------------------------------------------|
| `attributes.event_type = "PAYMENT_COMPLETED"`  | Exact attribute match                      |
| `attributes.amount_tier = "high" AND attributes.source = "upi-service"` | AND condition    |
| `hasPrefix(attributes.event_type, "PAYMENT")`  | Attribute value starts with prefix         |
| `attributes:priority`                          | Has attribute named "priority" (any value) |
| `NOT attributes:debug`                         | Does NOT have attribute named "debug"      |

### Create filtered subscriptions

```bash
# Only receives PAYMENT_COMPLETED events
gcloud pubsub subscriptions create payment-complete-sub \
  --topic=payment-events \
  --project=ford-upi-prod \
  --message-filter='attributes.event_type = "PAYMENT_COMPLETED"'

# Only receives high-value transactions
gcloud pubsub subscriptions create high-value-fraud-sub \
  --topic=payment-events \
  --project=ford-upi-prod \
  --message-filter='attributes.amount_tier = "high" AND attributes.source = "upi-service"'

# Only receives messages that have a priority attribute
gcloud pubsub subscriptions create priority-sub \
  --topic=payment-events \
  --project=ford-upi-prod \
  --message-filter='attributes:priority'

# Only receives messages where event_type starts with "PAYMENT"
gcloud pubsub subscriptions create all-payment-sub \
  --topic=payment-events \
  --project=ford-upi-prod \
  --message-filter='hasPrefix(attributes.event_type, "PAYMENT")'
```

### Important notes on filtering

- Filtered-out messages are **automatically acknowledged** (they don't pile up)
- Filters are applied **server-side** — no cost for filtered messages
- Filters use **attributes only** — you cannot filter on message body
- Filters **cannot be changed** after subscription creation — recreate instead

---

## 13. Dead Letter Topics (DLQ)

Dead Letter Topics catch messages that **repeatedly fail processing**, preventing poison pills from blocking your queue.

```
  payment-events (Topic)
        |
        v
  +-- notif-sub --+         +-- payment-deadletter (Topic) --+
  |               |         |                                  |
  | Process msg   |  Fail   |   +-- dlq-monitor-sub           |
  | Nack / expire |-------->|   |   (inspect failed msgs)     |
  | (5 attempts)  |         |   |                              |
  +--------------+          +----------------------------------+
```

### Step 1: Create the dead letter topic and subscription

```bash
gcloud pubsub topics create payment-deadletter \
  --project=ford-upi-prod

gcloud pubsub subscriptions create dlq-monitor-sub \
  --topic=payment-deadletter \
  --project=ford-upi-prod \
  --ack-deadline=60
```

### Step 2: Grant Pub/Sub service account necessary IAM roles

```bash
# Get the project number
PROJECT_NUMBER=$(gcloud projects describe ford-upi-prod --format='value(projectNumber)')
PUBSUB_SA="service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com"

# Grant publisher role on dead letter topic
gcloud pubsub topics add-iam-policy-binding payment-deadletter \
  --project=ford-upi-prod \
  --member="serviceAccount:${PUBSUB_SA}" \
  --role="roles/pubsub.publisher"

# Grant subscriber role on the source subscription
gcloud pubsub subscriptions add-iam-policy-binding notif-sub \
  --project=ford-upi-prod \
  --member="serviceAccount:${PUBSUB_SA}" \
  --role="roles/pubsub.subscriber"
```

### Step 3: Create or update subscription with dead letter policy

```bash
# New subscription with dead letter
gcloud pubsub subscriptions create notif-sub-with-dlq \
  --topic=payment-events \
  --project=ford-upi-prod \
  --dead-letter-topic=payment-deadletter \
  --max-delivery-attempts=5 \
  --ack-deadline=30

# Or update existing subscription
gcloud pubsub subscriptions update notif-sub \
  --project=ford-upi-prod \
  --dead-letter-topic=projects/ford-upi-prod/topics/payment-deadletter \
  --max-delivery-attempts=5
```

### Step 4: Inspect the dead letter queue

```bash
gcloud pubsub subscriptions pull dlq-monitor-sub \
  --project=ford-upi-prod \
  --limit=10 \
  --format=json \
  --auto-ack=false
```

The dead-lettered messages include a `CloudPubSubDeadLetterSourceSubscription` attribute showing which subscription forwarded them.

---

## 14. Message Ordering

By default, Pub/Sub does **not** guarantee message order. Enable ordering when sequence matters.

### Enable ordering on a subscription

```bash
gcloud pubsub subscriptions create ordered-audit-sub \
  --topic=payment-events \
  --project=ford-upi-prod \
  --enable-message-ordering
```

### Publish with ordering key

```bash
# All messages with the same ordering key are delivered in order
gcloud pubsub topics publish payment-events \
  --project=ford-upi-prod \
  --message='{"txnId":"TXN100","step":"INITIATED"}' \
  --ordering-key=txn-TXN100

gcloud pubsub topics publish payment-events \
  --project=ford-upi-prod \
  --message='{"txnId":"TXN100","step":"DEBITED"}' \
  --ordering-key=txn-TXN100

gcloud pubsub topics publish payment-events \
  --project=ford-upi-prod \
  --message='{"txnId":"TXN100","step":"CREDITED"}' \
  --ordering-key=txn-TXN100
```

### Implications of failure with ordering

```
  Message A (ordering-key=txn-100) --> Delivered --> Acked     (OK)
  Message B (ordering-key=txn-100) --> Delivered --> Nacked     (FAIL!)
  Message C (ordering-key=txn-100) --> BLOCKED until B is acked

  All subsequent messages with the SAME ordering key are held back.
  Messages with DIFFERENT ordering keys are unaffected.
```

- **If a message fails:** all later messages with the same ordering key are paused
- **Resolution:** ack the failing message (possibly to DLQ) to unblock the key
- **Scope:** ordering is per-key, not global — different keys are independent
- **Performance trade-off:** ordering reduces parallelism for that key

---

## 15. Snapshot & Seek / Time Travel

Snapshots and seek let you **replay** or **skip** messages — invaluable for disaster recovery and reprocessing.

### Create a snapshot of subscription state

```bash
gcloud pubsub snapshots create notif-checkpoint-20260408 \
  --subscription=notif-sub \
  --project=ford-upi-prod
```

### List snapshots

```bash
gcloud pubsub snapshots list --project=ford-upi-prod
```

### Seek to a snapshot (replay from that point)

```bash
# Rewind the subscription to the snapshot state
gcloud pubsub subscriptions seek notif-sub \
  --project=ford-upi-prod \
  --snapshot=notif-checkpoint-20260408
```

### Seek to a timestamp (time travel)

```bash
# Replay all messages published after this timestamp
gcloud pubsub subscriptions seek notif-sub \
  --project=ford-upi-prod \
  --time="2026-04-08T10:00:00Z"

# Skip ahead — ack everything before this timestamp
gcloud pubsub subscriptions seek notif-sub \
  --project=ford-upi-prod \
  --time="2026-04-08T14:00:00Z"
```

### Requirements for seek

- Topic must have **message retention** enabled (`--message-retention-duration`)
- Subscription must have **retain-acked-messages** if you want to replay already-acked messages
- Snapshots expire after **7 days** by default (max 7 days)

### Common use case: Bug-fix reprocessing

```bash
# 1. Deploy buggy code at 10am, discover bug at 2pm
# 2. Fix and redeploy the subscriber
# 3. Replay messages from 10am onward:
gcloud pubsub subscriptions seek notif-sub \
  --project=ford-upi-prod \
  --time="2026-04-08T10:00:00Z"
# 4. All messages from 10am are redelivered to the fixed subscriber
```

---

## 16. IAM — Least Privilege Access

### Per-topic publisher (UPI Service publishes to payment-events only)

```bash
# Create a dedicated service account for the publisher
gcloud iam service-accounts create upi-publisher-sa \
  --display-name="UPI Service Publisher" \
  --project=ford-upi-prod

# Grant publisher role ONLY on the specific topic
gcloud pubsub topics add-iam-policy-binding payment-events \
  --project=ford-upi-prod \
  --member="serviceAccount:upi-publisher-sa@ford-upi-prod.iam.gserviceaccount.com" \
  --role="roles/pubsub.publisher"
```

### Per-subscription subscriber (Notification Service reads notif-sub only)

```bash
# Create a dedicated service account for the subscriber
gcloud iam service-accounts create notif-subscriber-sa \
  --display-name="Notification Subscriber" \
  --project=ford-upi-prod

# Grant subscriber role ONLY on the specific subscription
gcloud pubsub subscriptions add-iam-policy-binding notif-sub \
  --project=ford-upi-prod \
  --member="serviceAccount:notif-subscriber-sa@ford-upi-prod.iam.gserviceaccount.com" \
  --role="roles/pubsub.subscriber"
```

### View IAM policy

```bash
gcloud pubsub topics get-iam-policy payment-events \
  --project=ford-upi-prod

gcloud pubsub subscriptions get-iam-policy notif-sub \
  --project=ford-upi-prod
```

### Principle: Never grant `roles/pubsub.admin` or `roles/pubsub.editor` to service accounts.

| Role                      | Permissions                              | Use for            |
|---------------------------|------------------------------------------|--------------------|
| `roles/pubsub.publisher`  | Publish messages to a topic              | Producer services  |
| `roles/pubsub.subscriber` | Pull/ack messages from a subscription    | Consumer services  |
| `roles/pubsub.viewer`     | List/describe topics and subscriptions   | Monitoring, CI/CD  |
| `roles/pubsub.editor`     | Create/update/delete topics and subs     | Terraform SA only  |
| `roles/pubsub.admin`      | Full control including IAM               | Platform team only |

---

## 17. Local Emulator

The Pub/Sub emulator lets you develop and test **without a GCP project** or internet connection.

### Install and start the emulator

```bash
# Install the emulator component
gcloud components install pubsub-emulator

# Start the emulator (runs on localhost:8085 by default)
gcloud beta emulators pubsub start --project=ford-upi-local
```

### Set the environment variable

```bash
# In a new terminal
export PUBSUB_EMULATOR_HOST=localhost:8085
```

### Run gcloud commands against the emulator

```bash
# These all hit the local emulator, not real GCP
gcloud pubsub topics create payment-events \
  --project=ford-upi-local

gcloud pubsub subscriptions create test-sub \
  --topic=payment-events \
  --project=ford-upi-local

gcloud pubsub topics publish payment-events \
  --project=ford-upi-local \
  --message='{"txnId":"TEST001","amount":100}'

gcloud pubsub subscriptions pull test-sub \
  --project=ford-upi-local \
  --auto-ack
```

### Use in Spring Boot tests

```bash
# In application-test.yml or as a JVM argument
spring.cloud.gcp.pubsub.emulator-host=localhost:8085
```

### Stop the emulator

```bash
# Unset the env variable to go back to real GCP
unset PUBSUB_EMULATOR_HOST
```

### Emulator limitations
- No IAM enforcement
- No message filtering
- No dead letter topics
- No ordering guarantees
- No exactly-once delivery

---

## 18. Spring Boot Integration

### Maven dependencies

```xml
<dependencies>
    <!-- Spring Cloud GCP Pub/Sub Starter -->
    <dependency>
        <groupId>com.google.cloud</groupId>
        <artifactId>spring-cloud-gcp-starter-pubsub</artifactId>
    </dependency>

    <!-- Spring Integration for Pub/Sub (channel adapters) -->
    <dependency>
        <groupId>com.google.cloud</groupId>
        <artifactId>spring-cloud-gcp-pubsub-stream-binder</artifactId>
    </dependency>

    <!-- Jackson for JSON serialization -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
</dependencies>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.google.cloud</groupId>
            <artifactId>spring-cloud-gcp-dependencies</artifactId>
            <version>5.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### Publisher — PaymentEventPublisher.java

```java
@Service
@Slf4j
public class PaymentEventPublisher {

    private final PubSubTemplate pubSubTemplate;
    private final ObjectMapper objectMapper;

    public PaymentEventPublisher(PubSubTemplate pubSubTemplate,
                                  ObjectMapper objectMapper) {
        this.pubSubTemplate = pubSubTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishPaymentCompleted(PaymentEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            Map<String, String> attributes = Map.of(
                "event_type", "PAYMENT_COMPLETED",
                "amount_tier", event.getAmount() > 10000 ? "high" : "standard",
                "source", "upi-service"
            );

            ListenableFuture<String> future =
                pubSubTemplate.publish("payment-events", payload, attributes);

            future.addCallback(
                messageId -> log.info("Published event {} with messageId {}",
                    event.getTxnId(), messageId),
                ex -> log.error("Failed to publish event {}",
                    event.getTxnId(), ex)
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event {}", event.getTxnId(), e);
            throw new RuntimeException("Serialization failed", e);
        }
    }
}
```

### Subscriber — NotificationSubscriber.java

```java
@Service
@Slf4j
public class NotificationSubscriber {

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    public NotificationSubscriber(ObjectMapper objectMapper,
                                   NotificationService notificationService) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @Bean
    public MessageChannel notificationInputChannel() {
        return new PublishSubscribeChannel();
    }

    @Bean
    public PubSubInboundChannelAdapter notificationAdapter(
            PubSubTemplate pubSubTemplate,
            @Qualifier("notificationInputChannel") MessageChannel channel) {

        PubSubInboundChannelAdapter adapter =
            new PubSubInboundChannelAdapter(pubSubTemplate, "notif-sub");
        adapter.setOutputChannel(channel);
        adapter.setAckMode(AckMode.MANUAL);
        adapter.setPayloadType(String.class);
        return adapter;
    }

    @ServiceActivator(inputChannel = "notificationInputChannel")
    public void handleMessage(Message<String> message) {
        BasicAcknowledgeablePubsubMessage ack =
            message.getHeaders().get(
                GcpPubSubHeaders.ORIGINAL_MESSAGE,
                BasicAcknowledgeablePubsubMessage.class);

        try {
            PaymentEvent event =
                objectMapper.readValue(message.getPayload(), PaymentEvent.class);

            log.info("Received payment event: {}", event.getTxnId());
            notificationService.sendSms(event);

            ack.ack();  // Acknowledge on success
            log.info("Acked message for txn: {}", event.getTxnId());

        } catch (Exception e) {
            log.error("Failed to process message", e);
            ack.nack();  // Nack on failure — message will be redelivered
        }
    }
}
```

---

## 19. Push Subscriptions with Cloud Run

### Setup the push subscription pointing to Cloud Run

```bash
# Deploy the Cloud Run service first
gcloud run deploy fraud-detection-service \
  --image=gcr.io/ford-upi-prod/fraud-detection:latest \
  --region=asia-south1 \
  --project=ford-upi-prod \
  --no-allow-unauthenticated

# Create a service account for Pub/Sub to authenticate with Cloud Run
gcloud iam service-accounts create pubsub-push-invoker \
  --display-name="Pub/Sub Push Invoker" \
  --project=ford-upi-prod

# Grant Cloud Run invoker role
gcloud run services add-iam-policy-binding fraud-detection-service \
  --region=asia-south1 \
  --project=ford-upi-prod \
  --member="serviceAccount:pubsub-push-invoker@ford-upi-prod.iam.gserviceaccount.com" \
  --role="roles/run.invoker"

# Create push subscription
gcloud pubsub subscriptions create fraud-push-sub \
  --topic=payment-events \
  --project=ford-upi-prod \
  --push-endpoint=https://fraud-detection-service-abc123.a.run.app/api/v1/pubsub/push \
  --push-auth-service-account=pubsub-push-invoker@ford-upi-prod.iam.gserviceaccount.com \
  --message-filter='attributes.amount_tier = "high"'
```

### Spring Boot Push Endpoint Controller

```java
@RestController
@RequestMapping("/api/v1/pubsub")
@Slf4j
public class PubSubPushController {

    private final ObjectMapper objectMapper;
    private final FraudDetectionService fraudService;

    public PubSubPushController(ObjectMapper objectMapper,
                                 FraudDetectionService fraudService) {
        this.objectMapper = objectMapper;
        this.fraudService = fraudService;
    }

    @PostMapping("/push")
    public ResponseEntity<Void> handlePush(
            @RequestBody Map<String, Object> body) {

        try {
            Map<String, Object> message =
                (Map<String, Object>) body.get("message");

            String data = (String) message.get("data");
            String decoded = new String(
                Base64.getDecoder().decode(data), StandardCharsets.UTF_8);

            PaymentEvent event =
                objectMapper.readValue(decoded, PaymentEvent.class);

            Map<String, String> attributes =
                (Map<String, String>) message.get("attributes");

            log.info("Push received: txn={}, tier={}",
                event.getTxnId(),
                attributes.getOrDefault("amount_tier", "unknown"));

            fraudService.analyze(event);

            // Return 200 to acknowledge the message
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Failed to process push message", e);
            // Return non-2xx to nack — Pub/Sub will retry
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
```

> **Key rule:** Return HTTP 2xx to ack. Return anything else (4xx, 5xx) to nack and trigger retry.

---

## 20. Monitoring

### Key metrics to watch

| Metric                                                    | What it tells you                        | Alert threshold           |
|-----------------------------------------------------------|------------------------------------------|---------------------------|
| `subscription/num_undelivered_messages`                   | Backlog size — are consumers keeping up? | > 10,000 for 5 min        |
| `subscription/oldest_unacked_message_age`                 | Staleness — is processing stuck?         | > 300 seconds              |
| `subscription/dead_letter_message_count`                  | Poison pills — are messages failing?     | > 0 (any dead letter)     |
| `topic/send_message_operation_count`                      | Publisher throughput                      | Sudden drops               |
| `subscription/pull_message_operation_count`                | Subscriber throughput                     | Sudden drops               |
| `subscription/push_request_count` (grouped by response)   | Push endpoint health                     | 5xx rate > 5%             |

### gcloud monitoring commands

```bash
# Check undelivered message count (backlog)
gcloud monitoring metrics list \
  --project=ford-upi-prod \
  --filter='metric.type = "pubsub.googleapis.com/subscription/num_undelivered_messages"'

# Quick check via subscription describe
gcloud pubsub subscriptions describe notif-sub \
  --project=ford-upi-prod \
  --format='value(numUndeliveredMessages)'
```

### Create alerting policy for backlog

```bash
gcloud alpha monitoring policies create \
  --project=ford-upi-prod \
  --display-name="Pub/Sub Backlog Alert - notif-sub" \
  --condition-display-name="Undelivered messages > 10000" \
  --condition-filter='resource.type = "pubsub_subscription" AND resource.labels.subscription_id = "notif-sub" AND metric.type = "pubsub.googleapis.com/subscription/num_undelivered_messages"' \
  --condition-threshold-value=10000 \
  --condition-threshold-duration=300s \
  --notification-channels=projects/ford-upi-prod/notificationChannels/CHANNEL_ID
```

### Create alerting policy for message age

```bash
gcloud alpha monitoring policies create \
  --project=ford-upi-prod \
  --display-name="Pub/Sub Stale Messages - notif-sub" \
  --condition-display-name="Oldest unacked > 5 min" \
  --condition-filter='resource.type = "pubsub_subscription" AND resource.labels.subscription_id = "notif-sub" AND metric.type = "pubsub.googleapis.com/subscription/oldest_unacked_message_age"' \
  --condition-threshold-value=300 \
  --condition-threshold-duration=60s \
  --notification-channels=projects/ford-upi-prod/notificationChannels/CHANNEL_ID
```

---

## 21. Best Practices

### Idempotency is mandatory

```
  Pub/Sub guarantees AT-LEAST-ONCE delivery.
  Your subscriber WILL receive duplicates.

  +-- Message A (messageId=123) delivered at 10:00:01 --> Processed
  +-- Message A (messageId=123) delivered at 10:00:05 --> DUPLICATE!
```

**Solution:** Use `messageId` or your own `txnId` as an idempotency key:

```java
@Service
public class IdempotentProcessor {
    private final RedisTemplate<String, String> redis;

    public boolean processIfNew(String messageId, Runnable action) {
        Boolean isNew = redis.opsForValue()
            .setIfAbsent("processed:" + messageId, "1", Duration.ofHours(24));
        if (Boolean.TRUE.equals(isNew)) {
            action.run();
            return true;
        }
        log.info("Duplicate message ignored: {}", messageId);
        return false;
    }
}
```

### Always configure Dead Letter Queues

- Every production subscription **must** have a DLT
- Set `--max-delivery-attempts` between 5 and 10
- Monitor the DLQ — alert on `dead_letter_message_count > 0`
- Have a process to inspect and replay DLQ messages

### Use schemas for data contracts

```bash
# Create a schema
gcloud pubsub schemas create payment-event-schema \
  --project=ford-upi-prod \
  --type=avro \
  --definition-file=payment-event.avsc

# Bind schema to topic
gcloud pubsub topics create payment-events-v2 \
  --project=ford-upi-prod \
  --schema=payment-event-schema \
  --message-encoding=json
```

### Other best practices

| Practice                        | Why                                                   |
|---------------------------------|-------------------------------------------------------|
| Set explicit ack deadlines      | Default 10s is too short for most processing          |
| Use attributes for routing      | Enables server-side filtering without parsing payload |
| Enable message retention        | Required for seek/replay during incident recovery     |
| Use ordering keys sparingly     | They reduce parallelism — only use when needed        |
| Set `--expiration-period=never` | Prevents auto-deletion of idle subscriptions          |
| Log messageId in every handler  | Essential for debugging duplicate/missing messages    |
| Size messages < 1 MB            | Store large payloads in GCS, pass the URI in message  |

---

## 22. Expert Takeaways

### Architecture Principles
1. **Pub/Sub is the backbone** — it decouples your UPI service from all downstream consumers
2. **Publish once, consume many** — one payment event drives notifications, audit, fraud, and analytics
3. **Failures are isolated** — a slow SMS gateway never blocks a payment response

### Operational Essentials
4. **DLQ is not optional** — every subscription needs a dead letter topic in production
5. **Monitor backlog and age** — these two metrics catch 90% of Pub/Sub issues
6. **Seek is your safety net** — message retention + seek lets you replay after bugs

### Development Patterns
7. **Idempotency first** — design every subscriber to handle duplicate messages safely
8. **Attributes for metadata** — put routing info in attributes, business data in the payload
9. **Emulator for local dev** — never publish to production topics from your laptop

### Security
10. **Least privilege always** — publishers get `pubsub.publisher`, subscribers get `pubsub.subscriber`, nobody gets `pubsub.admin`

```
  +-------------------------------------------------------+
  |                  UPI Payment Flow                       |
  |                                                         |
  |  User --> UPI Service --> [payment-events Topic]        |
  |                                |                        |
  |              +---------+-------+-------+---------+      |
  |              |         |               |         |      |
  |          Notify     Audit          Fraud      Analytics |
  |          Service    Service        Service    (BigQuery) |
  |              |         |               |         |      |
  |           [DLQ]     [DLQ]           [DLQ]     [DLQ]    |
  +-------------------------------------------------------+

  This is the target architecture. Build it step by step.
```

---

## Summary

| Topic                    | Key Command / Concept                                    |
|--------------------------|----------------------------------------------------------|
| Create topic             | `gcloud pubsub topics create <name>`                    |
| Create subscription      | `gcloud pubsub subscriptions create <name> --topic=...` |
| Publish                  | `gcloud pubsub topics publish <topic> --message=...`    |
| Pull                     | `gcloud pubsub subscriptions pull <sub> --auto-ack`     |
| Dead letter              | `--dead-letter-topic=... --max-delivery-attempts=5`     |
| Filtering                | `--message-filter='attributes.key = "value"'`           |
| Ordering                 | `--enable-message-ordering` + `--ordering-key=...`      |
| Seek / Replay            | `gcloud pubsub subscriptions seek <sub> --time=...`     |
| Emulator                 | `export PUBSUB_EMULATOR_HOST=localhost:8085`            |
| Spring Boot              | `spring-cloud-gcp-starter-pubsub` + `PubSubTemplate`   |

> **Next lab:** Create the `payment-events` topic, wire up publisher and subscriber, and test with the emulator.
