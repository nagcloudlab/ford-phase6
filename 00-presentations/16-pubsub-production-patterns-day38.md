# Production Pub/Sub Patterns + Deploy to GCP
## Day 38 — From Local Emulator to Live Cloud

---

## Recap: What We Built Yesterday

```
  POST /api/v1/transfer
        |
        v
  +-- upi-transfer-service --+       +---[ transaction-events TOPIC ]---+
  |  1. Validate             |       |                                   |
  |  2. Debit sender         |       |   +-- notif-sub -------> notification-service
  |  3. Credit receiver      |       |   |
  |  4. Publish event -------|------>|   +-- fraud-sub -------> fraud-detection-service
  +--------------------------+       |   |
  User gets response!                |   +-- analytics-sub ---> analytics-service
                                     +-----------------------------------+
```

### 4 Services. 1 Topic. 3 Subscriptions. Fan-out in action.

---

## Recap: The Four Factors We Proved

| Factor          | What We Measured                          | Result                        |
|-----------------|-------------------------------------------|-------------------------------|
| **Throughput**  | Messages published + consumed per second  | All 3 subscribers kept up     |
| **Latency**     | Time from publish to subscriber receive   | ~10-50ms on emulator          |
| **Durability**  | Subscriber down, comes back, gets messages| Messages waiting in backlog   |
| **Availability**| Publisher works even when subscribers fail| Pub/Sub decouples completely  |

> Yesterday we proved the architecture works locally.
> Today we make it production-ready and deploy it to real GCP.

---

## Today's Agenda

```
  +---------------------------------------------------+
  |  1. Dead Letter Queues (DLQ)                       |
  |  2. Message Ordering                               |
  |  3. Server-Side Filtering                          |
  |  4. Retry & Acknowledgement Deep Dive              |
  |  5. Deploy to Real GCP (Cloud Run)                 |
  |  6. Monitoring Pub/Sub in Production               |
  |  7. Pub/Sub vs Kafka vs RabbitMQ                   |
  |  8. Production Checklist                           |
  +---------------------------------------------------+
```

**Goal:** You will deploy 4 microservices to Cloud Run and watch events flow across live GCP infrastructure.

---

## 1. Dead Letter Queue — The Safety Net

### The Problem: Poison Messages

```
  Subscriber pulls message
        |
        v
  Try to process  --->  FAIL (malformed JSON, bug, DB down)
        |
        v
  Nack the message
        |
        v
  Pub/Sub redelivers
        |
        v
  Try to process  --->  FAIL again
        |
        v
  Nack... redeliver... fail... nack... redeliver... fail...
        |
        v
  INFINITE LOOP — this one bad message blocks all processing!
```

> One rotten apple spoils the barrel. A poison message can bring your entire subscriber to its knees.

---

## What Causes Poison Messages?

| Cause                          | Example                                                   |
|--------------------------------|-----------------------------------------------------------|
| **Malformed payload**          | Publisher sends invalid JSON; subscriber can't deserialize |
| **Schema mismatch**           | Publisher added a new field; old subscriber can't parse it |
| **Business rule violation**    | Amount is negative, UPI ID format changed                 |
| **Downstream dependency**      | Fraud service calls an ML API that's permanently down     |
| **Bug in subscriber code**     | NullPointerException on an edge case                      |

### Real-world Ford scenario:
A third-party bank API changes its response format. Every transaction event for that bank fails in the notification-service. Without DLQ, those messages retry forever, consuming resources and hiding the real problem.

---

## Dead Letter Queue — How It Works

```
  Publisher
     |
     v
  +-- transaction-events (Topic) --+
  |                                 |
  |   +-- fraud-sub (Subscription) |
  |   |   maxDeliveryAttempts: 5   |
  +---|-----------------------------+
      |
      v
  Subscriber receives message
      |
      +--- Process OK ---> ACK (done!)
      |
      +--- Process FAIL --> NACK
                |
                v
           Retry 1... NACK
           Retry 2... NACK
           Retry 3... NACK
           Retry 4... NACK
           Retry 5... NACK (max attempts reached!)
                |
                v
  +-- transaction-events-dlq (Topic) --+
  |                                     |
  |   +-- dlq-monitor-sub              |
  +-------------------------------------+
      |
      v
  Message lands here for investigation
  (with original attributes + delivery attempt count)
```

---

## Configuring DLQ — gcloud Commands

### Step 1: Create DLQ topic and subscription

```bash
# Create the DLQ topic
gcloud pubsub topics create transaction-events-dlq \
    --project=ford-upi-project

# Create a subscription to monitor DLQ messages
gcloud pubsub subscriptions create dlq-monitor-sub \
    --topic=transaction-events-dlq \
    --project=ford-upi-project
```

### Step 2: Configure DLQ on the subscriber's subscription

```bash
# Update fraud-sub to route failed messages to DLQ after 5 attempts
gcloud pubsub subscriptions update fraud-sub \
    --dead-letter-topic=transaction-events-dlq \
    --max-delivery-attempts=5 \
    --project=ford-upi-project
```

### Step 3: Grant permissions (Pub/Sub needs to publish to DLQ topic)

```bash
# Get the Pub/Sub service account
PROJECT_NUMBER=$(gcloud projects describe ford-upi-project --format="value(projectNumber)")

# Grant publisher role on DLQ topic
gcloud pubsub topics add-iam-policy-binding transaction-events-dlq \
    --member="serviceAccount:service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com" \
    --role="roles/pubsub.publisher"

# Grant subscriber role on the source subscription
gcloud pubsub subscriptions add-iam-policy-binding fraud-sub \
    --member="serviceAccount:service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com" \
    --role="roles/pubsub.subscriber"
```

---

## DLQ — What Lands There?

When a message moves to the DLQ, it carries its **original attributes** plus:

| Attribute                             | Value                              |
|---------------------------------------|------------------------------------|
| `CloudPubSubDeadLetterSourceDeliveryCount` | Number of delivery attempts (e.g., `5`) |
| `CloudPubSubDeadLetterSourceSubscription`  | The subscription that failed       |
| `CloudPubSubDeadLetterSourceTopicPublishTime` | Original publish timestamp      |

### Pull a DLQ message for investigation:

```bash
gcloud pubsub subscriptions pull dlq-monitor-sub --auto-ack --format=json
```

### What you'll see:

```json
{
  "message": {
    "data": "eyJ0cmFuc2FjdGlvbklkIjoiVFhOLTEyMzQiLC4uLn0=",
    "attributes": {
      "eventType": "TRANSFER",
      "status": "SUCCESS",
      "senderUpiId": "nag@upi",
      "amountRange": "HIGH",
      "CloudPubSubDeadLetterSourceDeliveryCount": "5"
    }
  }
}
```

> Decode the Base64 data, investigate why it failed, fix the bug, and republish.

---

## DLQ Monitoring — The Critical Alert

```
  DLQ Depth = 0        -->  Everything is healthy
  DLQ Depth > 0        -->  Something is failing repeatedly
  DLQ Depth growing    -->  Active problem — investigate NOW
```

### This is a production MUST:

```bash
# Create an alert policy: DLQ has messages
gcloud alpha monitoring policies create \
    --notification-channels=CHANNEL_ID \
    --display-name="DLQ Messages Detected" \
    --condition-display-name="DLQ depth > 0" \
    --condition-filter='resource.type="pubsub_subscription"
        AND resource.labels.subscription_id="dlq-monitor-sub"
        AND metric.type="pubsub.googleapis.com/subscription/num_undelivered_messages"' \
    --condition-threshold-value=0 \
    --condition-threshold-comparison=COMPARISON_GT
```

> **Rule of thumb:** If your DLQ has messages and nobody is looking at them, you don't have a DLQ — you have a graveyard.

---

## 2. Message Ordering — When Sequence Matters

### The Problem

```
  Publisher sends 3 messages for nag@upi:
     1. INITIATED   (10:00:01)
     2. PROCESSING   (10:00:02)
     3. COMPLETED    (10:00:03)

  Without ordering, subscriber might receive:
     COMPLETED  (10:00:03)
     INITIATED  (10:00:01)
     PROCESSING (10:00:02)

  If subscriber updates transaction status:
     Status = COMPLETED  (correct at this point)
     Status = INITIATED  (WRONG! Overwrites COMPLETED!)
     Status = PROCESSING (WRONG! Should be COMPLETED!)
```

> The last write wins. If messages arrive out of order, the final state is wrong.

---

## Ordering Keys — The Solution

```
  Publisher:
     Message 1: data=INITIATED,   orderingKey="nag@upi"
     Message 2: data=PROCESSING,  orderingKey="nag@upi"
     Message 3: data=COMPLETED,   orderingKey="nag@upi"

  Message 4: data=INITIATED,   orderingKey="alice@upi"  <-- different key
  Message 5: data=PROCESSING,  orderingKey="alice@upi"

  Pub/Sub guarantees:
     Messages with SAME ordering key are delivered IN ORDER to subscriber
     Messages with DIFFERENT ordering keys can be delivered in parallel

  +-- nag@upi -----+     +-- alice@upi ----+
  | INITIATED       |     | INITIATED       |
  | PROCESSING      |     | PROCESSING      |
  | COMPLETED       |     |                 |
  +-----------------+     +-----------------+
        |                       |
        v                       v
   Same subscriber          Can be different
   (in order)               subscriber (in order)
```

---

## Ordering — Publisher Code

```java
// In TransactionEventPublisher.java — add ordering key

pubSubTemplate.publish(
    topicName,
    json,
    attributes,
    event.getSenderUpiId()  // <-- ordering key = sender's UPI ID
);
```

### Enable ordering on the topic:

```bash
# Create topic with message ordering enabled
gcloud pubsub topics create transaction-events \
    --message-ordering \
    --project=ford-upi-project

# Subscription must also enable ordering
gcloud pubsub subscriptions create notif-sub \
    --topic=transaction-events \
    --enable-message-ordering \
    --project=ford-upi-project
```

---

## Ordering — The Trade-Off

| Aspect               | Without Ordering                   | With Ordering                       |
|----------------------|------------------------------------|-------------------------------------|
| **Throughput**       | Maximum — fully parallel           | Reduced — serialized per key        |
| **Latency**          | Lower — no coordination needed     | Slightly higher — sequential delivery|
| **Complexity**       | Simple                             | Must handle ordering key carefully  |
| **Error handling**   | Independent per message            | One failure blocks all messages with same key |

### When to use ordering:

| Use Case                          | Ordering? | Why                                          |
|-----------------------------------|-----------|----------------------------------------------|
| Transaction state changes         | YES       | INITIATED -> PROCESSING -> COMPLETED         |
| Financial ledger updates          | YES       | Balance must reflect correct sequence        |
| Independent notifications         | NO        | SMS for different users — order doesn't matter|
| Analytics event counting          | NO        | Aggregate counts don't care about order      |
| Audit log entries                 | MAYBE     | Depends on whether you need strict sequence  |

> **Rule:** Use ordering only when your subscriber's correctness depends on processing sequence. It costs throughput.

---

## Ordering — The Stall Problem

```
  Ordering key: "nag@upi"
  Queue: [msg1] [msg2] [msg3] [msg4] [msg5]
                   ^
                   |
              msg2 fails and is nack'd

  Result: msg3, msg4, msg5 are BLOCKED
  They cannot be delivered until msg2 succeeds

  This is BY DESIGN — ordering means "in order or not at all"
```

### Mitigation:
- Set a reasonable `maxDeliveryAttempts` (e.g., 5)
- Configure DLQ so the poisoned message moves out
- Once msg2 goes to DLQ, msg3/4/5 resume delivery

> Ordering + DLQ = must-have combination. Never use ordering without a DLQ.

---

## 3. Server-Side Filtering — Subscribe to What You Need

### The Problem: Wasteful Processing

```
  transaction-events topic
       |
       |  ALL messages (HIGH, MEDIUM, LOW amounts)
       |
       v
  fraud-detection-service
       |
       +--- amount >= 10000?  YES --> process (fraud check)
       |
       +--- amount < 10000?   NO  --> ack and discard

  Result: Fraud service receives 90% of messages it doesn't care about
  Wasted: Network, CPU, memory, cost
```

---

## Server-Side Filtering — The Solution

```
  transaction-events topic
       |
       +--- attribute filter: amountRange = "HIGH"
       |                         |
       |                         v
       |                   fraud-sub (only HIGH amount messages delivered)
       |
       +--- no filter
       |         |
       |         v
       |   notif-sub (ALL messages delivered)
       |
       +--- attribute filter: eventType = "TRANSFER" AND status = "SUCCESS"
                 |
                 v
           analytics-sub (only successful transfers)
```

### Pub/Sub does the filtering on the server side — your subscriber only receives matching messages!

---

## Filtering — Our Message Attributes

Remember what our publisher sends:

```java
Map<String, String> attributes = Map.of(
    "eventType",     "TRANSFER",
    "status",        event.getStatus(),        // SUCCESS, FAILED
    "senderUpiId",   event.getSenderUpiId(),   // nag@upi
    "receiverUpiId", event.getReceiverUpiId(), // alice@upi
    "amountRange",   categorizeAmount(...)      // HIGH, MEDIUM, LOW
);
```

### Filter syntax examples:

| Filter Expression                                        | What It Does                            |
|----------------------------------------------------------|-----------------------------------------|
| `attributes.amountRange = "HIGH"`                        | Only high-value transactions            |
| `attributes.status = "FAILED"`                           | Only failed transfers                   |
| `attributes.senderUpiId = "nag@upi"`                    | Only nag's outgoing transfers           |
| `attributes.amountRange = "HIGH" AND attributes.status = "SUCCESS"` | High-value successful only   |
| `NOT attributes.eventType = "TRANSFER"`                  | Everything except transfers             |

---

## Filtering — gcloud Commands

### Create a filtered subscription for fraud detection:

```bash
# Only deliver HIGH amount transactions to fraud service
gcloud pubsub subscriptions create fraud-sub \
    --topic=transaction-events \
    --filter='attributes.amountRange = "HIGH"' \
    --project=ford-upi-project
```

### Create an unfiltered subscription for notifications:

```bash
# Notification service gets ALL messages
gcloud pubsub subscriptions create notif-sub \
    --topic=transaction-events \
    --project=ford-upi-project
```

### Create a filtered subscription for analytics:

```bash
# Analytics only cares about successful transfers
gcloud pubsub subscriptions create analytics-sub \
    --topic=transaction-events \
    --filter='attributes.status = "SUCCESS"' \
    --project=ford-upi-project
```

---

## Filtering — Limitations

| Limitation                              | Details                                              |
|-----------------------------------------|------------------------------------------------------|
| **Attributes only**                     | Cannot filter on message body/data                   |
| **String values only**                  | No numeric comparisons (can't say `amount > 10000`)  |
| **Max 20 filters per subscription**     | Complex boolean expressions count toward this        |
| **No regex**                            | Exact match, `hasPrefix`, or boolean operators only  |
| **Immutable after creation**            | Must delete and recreate subscription to change filter|
| **Filtered messages still cost money**  | Publisher is charged for all published messages       |

### Workaround for the "no numeric filter" limitation:

```java
// This is exactly why we categorize amounts in the publisher!
private String categorizeAmount(double amount) {
    if (amount >= 10000) return "HIGH";     // Filterable string
    if (amount >= 1000)  return "MEDIUM";   // Filterable string
    return "LOW";                            // Filterable string
}
```

> Design your attributes for filterability. Think about what subscribers will need before you publish.

---

## 4. Retry & Acknowledgement Deep Dive

### The Ack Deadline

```
  Pub/Sub delivers message to subscriber
       |
       |--- Ack deadline starts (default: 10 seconds) ---+
       |                                                   |
       v                                                   v
  Subscriber processing...                         If no ACK by deadline:
       |                                            Pub/Sub assumes failure
       +--- ACK (within deadline) --> DONE          and REDELIVERS the message
       |
       +--- NACK --> Immediate redelivery
       |
       +--- No response --> Redelivery after deadline expires
```

### Configuring ack deadline:

```bash
# Set ack deadline to 60 seconds (for slow processing)
gcloud pubsub subscriptions create fraud-sub \
    --topic=transaction-events \
    --ack-deadline=60 \
    --project=ford-upi-project
```

| Ack Deadline | Good For                                      |
|-------------|-----------------------------------------------|
| 10s         | Fast processing (notifications, logging)       |
| 30-60s      | API calls, DB writes                           |
| 120-300s    | ML model inference, batch processing           |
| 600s (max)  | Very heavy processing (avoid if possible)      |

---

## Retry with Exponential Backoff

### Without backoff (default):

```
  NACK --> redeliver immediately
  NACK --> redeliver immediately
  NACK --> redeliver immediately
  ...
  Result: Hammers the subscriber with rapid retries
          If the downstream service is down, this makes it worse
```

### With exponential backoff:

```bash
gcloud pubsub subscriptions create fraud-sub \
    --topic=transaction-events \
    --min-retry-delay=10s \
    --max-retry-delay=600s \
    --project=ford-upi-project
```

```
  NACK --> wait 10s  --> redeliver
  NACK --> wait 20s  --> redeliver
  NACK --> wait 40s  --> redeliver
  NACK --> wait 80s  --> redeliver
  NACK --> wait 160s --> redeliver
  NACK --> wait 320s --> redeliver
  NACK --> wait 600s --> redeliver (capped at max)
```

> Backoff gives the downstream system time to recover. Without it, you're DDoS-ing yourself.

---

## At-Least-Once vs Exactly-Once

### At-Least-Once (Default)

```
  Pub/Sub delivers message
  Subscriber processes it
  Subscriber sends ACK
  ... but ACK is lost (network blip)
  Pub/Sub thinks subscriber failed
  Pub/Sub delivers the SAME message again
  Subscriber processes it AGAIN  <-- DUPLICATE!
```

### Exactly-Once (Available on Pull Subscriptions)

```bash
gcloud pubsub subscriptions create notif-sub \
    --topic=transaction-events \
    --enable-exactly-once-delivery \
    --project=ford-upi-project
```

| Delivery Mode     | Guarantee                          | Performance              |
|-------------------|------------------------------------|--------------------------|
| At-least-once     | Every message delivered 1+ times   | Higher throughput        |
| Exactly-once      | Every message delivered exactly once| Lower throughput, higher latency |

> **Reality check:** Even with exactly-once delivery, your subscriber code should be idempotent. Belt AND suspenders.

---

## Idempotency — The Non-Negotiable Pattern

### Why idempotency matters:

Even with exactly-once delivery, things can go wrong:
- Your service restarts mid-processing
- A load balancer retries a request
- A message is delivered twice during a rebalance

### The Pattern: Use transactionId as idempotency key

```java
@Service
public class NotificationSubscriber {

    // Track processed transaction IDs
    private final Set<String> processedTxnIds = ConcurrentHashMap.newKeySet();
    // In production: use Redis or a database table instead of in-memory set

    public void processMessage(TransactionEvent event) {
        String txnId = event.getTransactionId();

        // IDEMPOTENCY CHECK — have we already processed this?
        if (processedTxnIds.contains(txnId)) {
            log.warn("DUPLICATE detected | txnId={} | Skipping", txnId);
            return;  // ACK the message but don't process again
        }

        // Process the message
        sendSmsNotification(event);

        // Mark as processed
        processedTxnIds.add(txnId);
        log.info("Processed | txnId={}", txnId);
    }
}
```

### Production idempotency with a database:

```sql
-- Create an idempotency table
CREATE TABLE processed_messages (
    transaction_id VARCHAR(64) PRIMARY KEY,
    processed_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Before processing: check if already done
SELECT 1 FROM processed_messages WHERE transaction_id = 'TXN-1234';

-- After processing: record it
INSERT INTO processed_messages (transaction_id) VALUES ('TXN-1234');
```

> **Rule:** If processing a message twice produces different results than processing it once, you have a bug.

---

## 5. Deploy to Real GCP — Cloud Run

### The Moment of Truth

```
  LOCAL (yesterday)                    GCP (today)
  +-------------------+              +-------------------+
  | Pub/Sub Emulator  |     --->     | Real GCP Pub/Sub  |
  | localhost:8085    |              | Managed, global   |
  +-------------------+              +-------------------+
  | 4 Spring Boot     |     --->     | 4 Cloud Run       |
  | processes on      |              | services, each    |
  | your laptop       |              | auto-scaling      |
  +-------------------+              +-------------------+
```

---

## Step 1: Create Real Pub/Sub Resources

```bash
# Set your project
export PROJECT_ID=ford-upi-project
gcloud config set project $PROJECT_ID

# Create the main topic
gcloud pubsub topics create transaction-events

# Create the DLQ topic
gcloud pubsub topics create transaction-events-dlq

# Create subscription for notification-service
gcloud pubsub subscriptions create notif-sub \
    --topic=transaction-events \
    --ack-deadline=30 \
    --dead-letter-topic=transaction-events-dlq \
    --max-delivery-attempts=5

# Create subscription for fraud-detection-service (with filter!)
gcloud pubsub subscriptions create fraud-sub \
    --topic=transaction-events \
    --ack-deadline=60 \
    --filter='attributes.amountRange = "HIGH"' \
    --dead-letter-topic=transaction-events-dlq \
    --max-delivery-attempts=5

# Create subscription for analytics-service
gcloud pubsub subscriptions create analytics-sub \
    --topic=transaction-events \
    --ack-deadline=30 \
    --dead-letter-topic=transaction-events-dlq \
    --max-delivery-attempts=5

# Create DLQ monitor subscription
gcloud pubsub subscriptions create dlq-monitor-sub \
    --topic=transaction-events-dlq

# Verify everything
gcloud pubsub topics list
gcloud pubsub subscriptions list
```

---

## Step 2: Containerize All 4 Services

### Dockerfile (same for all Spring Boot services):

```dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY target/*.jar app.jar

# Cloud Run sets PORT environment variable
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar", "--server.port=${PORT}"]
```

### Build all 4 services:

```bash
# Build each service
cd upi-transfer-service   && ./mvnw clean package -DskipTests && cd ..
cd notification-service    && ./mvnw clean package -DskipTests && cd ..
cd fraud-detection-service && ./mvnw clean package -DskipTests && cd ..
cd analytics-service       && ./mvnw clean package -DskipTests && cd ..
```

---

## Step 3: Push to Artifact Registry

```bash
# Create the Artifact Registry repository (one-time)
gcloud artifacts repositories create ford-upi-repo \
    --repository-format=docker \
    --location=asia-south1 \
    --description="Ford UPI Service Images"

# Configure Docker to use Artifact Registry
gcloud auth configure-docker asia-south1-docker.pkg.dev

# Set registry path
REGISTRY=asia-south1-docker.pkg.dev/$PROJECT_ID/ford-upi-repo

# Build and push all 4 images
docker build -t $REGISTRY/upi-transfer-service:v1   ./upi-transfer-service
docker build -t $REGISTRY/notification-service:v1    ./notification-service
docker build -t $REGISTRY/fraud-detection-service:v1 ./fraud-detection-service
docker build -t $REGISTRY/analytics-service:v1       ./analytics-service

docker push $REGISTRY/upi-transfer-service:v1
docker push $REGISTRY/notification-service:v1
docker push $REGISTRY/fraud-detection-service:v1
docker push $REGISTRY/analytics-service:v1
```

---

## Step 4: Deploy Publisher to Cloud Run

```bash
# Deploy upi-transfer-service (the publisher)
gcloud run deploy upi-transfer-service \
    --image=$REGISTRY/upi-transfer-service:v1 \
    --region=asia-south1 \
    --platform=managed \
    --allow-unauthenticated \
    --set-env-vars="SPRING_PROFILES_ACTIVE=pubsub" \
    --set-env-vars="PUBSUB_TOPIC=transaction-events" \
    --memory=512Mi \
    --min-instances=1 \
    --max-instances=5
```

### Key points:
- `SPRING_PROFILES_ACTIVE=pubsub` — activates the publisher bean
- **No** `PUBSUB_EMULATOR_HOST` — on Cloud Run, Spring Cloud GCP auto-detects real Pub/Sub
- `--min-instances=1` — avoid cold starts for the main API
- `--allow-unauthenticated` — so we can call the API (use IAM in real production)

---

## Step 5: Deploy 3 Subscribers to Cloud Run

```bash
# Deploy notification-service
gcloud run deploy notification-service \
    --image=$REGISTRY/notification-service:v1 \
    --region=asia-south1 \
    --platform=managed \
    --no-allow-unauthenticated \
    --set-env-vars="PUBSUB_SUBSCRIPTION=notif-sub" \
    --memory=512Mi \
    --min-instances=1

# Deploy fraud-detection-service
gcloud run deploy fraud-detection-service \
    --image=$REGISTRY/fraud-detection-service:v1 \
    --region=asia-south1 \
    --platform=managed \
    --no-allow-unauthenticated \
    --set-env-vars="PUBSUB_SUBSCRIPTION=fraud-sub" \
    --set-env-vars="FRAUD_HIGH_AMOUNT_THRESHOLD=5000" \
    --memory=512Mi \
    --min-instances=1

# Deploy analytics-service
gcloud run deploy analytics-service \
    --image=$REGISTRY/analytics-service:v1 \
    --region=asia-south1 \
    --platform=managed \
    --no-allow-unauthenticated \
    --set-env-vars="PUBSUB_SUBSCRIPTION=analytics-sub" \
    --memory=512Mi \
    --min-instances=1
```

### Why `--no-allow-unauthenticated` for subscribers?
They are internal services. No external HTTP calls needed — they pull from Pub/Sub.

---

## Step 6: IAM — Least Privilege

```bash
# Get the default Cloud Run service account
SA_EMAIL=$(gcloud iam service-accounts list \
    --filter="displayName:Default compute service account" \
    --format="value(email)")

# Or create a dedicated service account (recommended)
gcloud iam service-accounts create upi-pubsub-sa \
    --display-name="UPI Pub/Sub Service Account"

SA_EMAIL=upi-pubsub-sa@${PROJECT_ID}.iam.gserviceaccount.com

# Grant Pub/Sub Publisher role (for upi-transfer-service)
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/pubsub.publisher"

# Grant Pub/Sub Subscriber role (for notification, fraud, analytics services)
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/pubsub.subscriber"

# Grant Pub/Sub Viewer role (to list topics/subscriptions)
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/pubsub.viewer"
```

### Redeploy with dedicated service account:

```bash
gcloud run services update upi-transfer-service \
    --service-account=$SA_EMAIL \
    --region=asia-south1
```

---

## Step 6: Make a Transfer — Watch Events Flow!

```bash
# Get the Cloud Run URL
URL=$(gcloud run services describe upi-transfer-service \
    --region=asia-south1 --format="value(status.url)")

# Make a transfer!
curl -X POST $URL/api/v1/transfer \
    -H "Content-Type: application/json" \
    -d '{
        "senderUpiId": "nag@upi",
        "receiverUpiId": "alice@upi",
        "amount": 15000,
        "note": "First production transfer!"
    }'
```

### Now watch the logs across all 4 services:

```bash
# Terminal 1: Publisher logs
gcloud run services logs read upi-transfer-service --region=asia-south1 --limit=20

# Terminal 2: Notification logs
gcloud run services logs read notification-service --region=asia-south1 --limit=20

# Terminal 3: Fraud detection logs (should trigger — amount is HIGH)
gcloud run services logs read fraud-detection-service --region=asia-south1 --limit=20

# Terminal 4: Analytics logs
gcloud run services logs read analytics-service --region=asia-south1 --limit=20
```

---

## What You Should See

```
  +-- upi-transfer-service logs --+
  |  PUBLISHED | topic=transaction-events | txnId=TXN-abc123    |
  |  from=nag@upi | to=alice@upi | amount=15000                |
  |  attributes={amountRange=HIGH, status=SUCCESS, ...}         |
  +-------------------------------------------------------------+

  +-- notification-service logs --+
  |  NOTIFICATION SENT!                                          |
  |  SMS to nag@upi: You sent ₹15000 to alice@upi              |
  |  SMS to alice@upi: You received ₹15000 from nag@upi        |
  |  Latency: 85ms                                               |
  +-------------------------------------------------------------+

  +-- fraud-detection-service logs --+
  |  FRAUD CHECK | TxnId: TXN-abc123                             |
  |  ALERT: HIGH VALUE TRANSACTION! ₹15000 exceeds threshold    |
  |  Action: Flagged for manual review                           |
  +-------------------------------------------------------------+

  +-- analytics-service logs --+
  |  ANALYTICS | TxnId: TXN-abc123                               |
  |  Recorded: nag@upi -> alice@upi | ₹15000 | SUCCESS          |
  +-------------------------------------------------------------+
```

> One API call. Four services. Zero coupling. Real GCP. This is event-driven architecture.

---

## Local vs Cloud Run — What Changed?

| Aspect                  | Local (Emulator)                    | Cloud Run (Real GCP)                |
|-------------------------|-------------------------------------|-------------------------------------|
| Pub/Sub                 | `PUBSUB_EMULATOR_HOST=localhost:8085`| Real GCP (no env var needed)       |
| Authentication          | None needed                         | Service account + IAM              |
| Scaling                 | Single process each                 | Auto-scales 0 to N instances       |
| Networking              | localhost                           | Google's global network            |
| Monitoring              | Log files                           | Cloud Logging + Cloud Monitoring   |
| Cost                    | Free                                | Pay per request + message          |
| Reliability             | Your laptop                         | Google SLA (99.95%)                |

### The beauty of Spring Cloud GCP:
Your Java code is **identical**. Zero code changes between local and production.
The only difference is configuration (environment variables).

---

## 6. Monitoring Pub/Sub in Production

### The 5 Metrics That Matter

```
  +---------------------------------------------------------------+
  |  METRIC                    |  MEANING                         |
  |----------------------------|----------------------------------|
  |  publish_message_count     |  Messages published per second   |
  |  pull_ack_message_count    |  Messages acknowledged per second|
  |  num_undelivered_messages  |  Backlog size (unacked messages) |
  |  oldest_unacked_message_age|  Age of oldest unacked message   |
  |  dead_letter_message_count |  Messages sent to DLQ            |
  +---------------------------------------------------------------+
```

---

## Monitoring — What Each Metric Tells You

### Healthy system:

```
  Publish rate:    100 msg/s
  Ack rate:        100 msg/s    <-- keeping up
  Unacked:         5            <-- small, in-flight messages
  Oldest unacked:  2 seconds    <-- fresh
  DLQ depth:       0            <-- no poison messages
```

### Subscriber falling behind:

```
  Publish rate:    100 msg/s
  Ack rate:        60 msg/s     <-- NOT keeping up!
  Unacked:         5000         <-- growing backlog!
  Oldest unacked:  300 seconds  <-- 5 minutes old!
  DLQ depth:       0

  Action: Scale up subscriber instances or optimize processing
```

### Poison message scenario:

```
  Publish rate:    100 msg/s
  Ack rate:        99 msg/s     <-- almost keeping up
  Unacked:         10
  Oldest unacked:  15 seconds
  DLQ depth:       3            <-- messages failing repeatedly!

  Action: Pull DLQ messages, investigate, fix the bug
```

---

## Monitoring — Cloud Console

### Navigate to Pub/Sub monitoring:

```
  Cloud Console
    |
    +-- Pub/Sub
         |
         +-- Topics
         |     |
         |     +-- transaction-events
         |           |
         |           +-- Monitoring tab  <-- publish rate, message size
         |
         +-- Subscriptions
               |
               +-- notif-sub
               |     |
               |     +-- Monitoring tab  <-- ack rate, unacked count, age
               |
               +-- fraud-sub
               +-- analytics-sub
               +-- dlq-monitor-sub  <-- check this daily!
```

---

## Monitoring — Alert Policies

### Create essential alerts:

```bash
# Alert 1: Unacked messages growing (subscriber can't keep up)
gcloud alpha monitoring policies create \
    --display-name="Pub/Sub Backlog Alert" \
    --condition-display-name="Unacked > 100 for 5 min" \
    --condition-filter='resource.type="pubsub_subscription"
        AND metric.type="pubsub.googleapis.com/subscription/num_undelivered_messages"' \
    --condition-threshold-value=100 \
    --condition-threshold-comparison=COMPARISON_GT \
    --condition-threshold-duration=300s

# Alert 2: Oldest unacked message too old (redelivery happening)
gcloud alpha monitoring policies create \
    --display-name="Pub/Sub Message Age Alert" \
    --condition-display-name="Oldest unacked > 300s" \
    --condition-filter='resource.type="pubsub_subscription"
        AND metric.type="pubsub.googleapis.com/subscription/oldest_unacked_message_age"' \
    --condition-threshold-value=300 \
    --condition-threshold-comparison=COMPARISON_GT

# Alert 3: DLQ has messages (investigate immediately)
gcloud alpha monitoring policies create \
    --display-name="DLQ Messages Alert" \
    --condition-display-name="DLQ depth > 0" \
    --condition-filter='resource.type="pubsub_subscription"
        AND resource.labels.subscription_id="dlq-monitor-sub"
        AND metric.type="pubsub.googleapis.com/subscription/num_undelivered_messages"' \
    --condition-threshold-value=0 \
    --condition-threshold-comparison=COMPARISON_GT
```

---

## Structured Logging — Tracing Across Services

### Every log line should include messageId and transactionId:

```java
// In subscriber processing
log.info("PROCESSING | txnId={} | messageId={} | subscription={} | latencyMs={}",
    event.getTransactionId(),
    message.getPubsubMessage().getMessageId(),
    subscriptionName,
    latencyMs
);
```

### Searching in Cloud Logging:

```bash
# Find all logs for a specific transaction across all 4 services
gcloud logging read 'jsonPayload.message=~"TXN-abc123"' \
    --project=$PROJECT_ID \
    --limit=50 \
    --format=json

# Find all DLQ-related logs
gcloud logging read 'jsonPayload.message=~"NACK|FAIL|DLQ"' \
    --project=$PROJECT_ID \
    --limit=20
```

> One transactionId lets you trace a message from publisher through all 3 subscribers. This is your lifeline in production debugging.

---

## 7. Pub/Sub vs Kafka vs RabbitMQ

### When to Use What

| Dimension                | GCP Pub/Sub               | Apache Kafka              | RabbitMQ                  |
|--------------------------|---------------------------|---------------------------|---------------------------|
| **Managed**              | Fully managed (zero ops)  | Self-managed or Confluent | Self-managed              |
| **Throughput**           | Millions/sec              | Millions/sec (highest)    | ~50K/sec                  |
| **Ordering**             | Per ordering key          | Per partition             | Per queue                 |
| **Message replay**       | Seek to timestamp         | Full replay (log-based)   | Not supported             |
| **Retention**            | Up to 31 days             | Unlimited                 | Until consumed            |
| **Delivery guarantee**   | At-least / exactly-once   | At-least / exactly-once   | At-least / at-most-once   |
| **Dead letter**          | Built-in                  | Manual (code it yourself) | Built-in (DLX)            |
| **Server-side filter**   | Yes                       | No                        | Routing keys              |
| **Protocol**             | gRPC / REST               | Kafka binary protocol     | AMQP                      |
| **Ops burden**           | Zero                      | High (ZooKeeper, brokers) | Medium                    |
| **Cost model**           | Pay per message ($40/TiB) | Infra cost (VMs, disks)   | Infra cost                |
| **Ecosystem**            | GCP-native                | Kafka Streams, Connect    | Spring AMQP, wide support |
| **Multi-cloud**          | GCP only                  | Any cloud / on-prem       | Any cloud / on-prem       |
| **Best for**             | GCP event-driven apps     | High-throughput streaming | Complex routing patterns  |

---

## The Decision Framework

```
  Do you need message replay / event sourcing?
       |
       +-- YES --> Kafka
       |             (replay any message from any point in time)
       |
       +-- NO
            |
            Are you on GCP and want zero ops?
                 |
                 +-- YES --> Pub/Sub
                 |             (fully managed, global, simple)
                 |
                 +-- NO
                      |
                      Do you need complex routing (headers, topics, exchanges)?
                           |
                           +-- YES --> RabbitMQ
                           |             (exchange types, bindings, routing keys)
                           |
                           +-- NO --> Pub/Sub or managed Kafka (Confluent)
```

### For our UPI Transfer Service:
- We're on GCP -- Pub/Sub is native
- We don't need message replay -- at-least-once is fine
- We need fan-out -- Pub/Sub does it perfectly
- We want zero ops -- Pub/Sub is fully managed
- **Verdict: Pub/Sub is the right choice.**

---

## When You WOULD Choose Kafka Over Pub/Sub

| Scenario                                    | Why Kafka                                    |
|---------------------------------------------|----------------------------------------------|
| Event sourcing / CQRS architecture          | Need to replay entire event history          |
| Stream processing (real-time aggregations)  | Kafka Streams / ksqlDB built for this        |
| Multi-cloud / hybrid deployment             | Pub/Sub is GCP-only                          |
| > 100K msg/sec sustained with replay        | Kafka's log-based model excels here          |
| Regulatory requirement for message retention| Kafka can retain forever                     |

### Ford context:
At Ford's scale, some teams use Kafka for vehicle telemetry (millions of data points per second from connected cars). For our UPI transfer service, Pub/Sub handles the volume with zero infrastructure management.

---

## 8. Production Checklist

### Before you go to production, every Pub/Sub setup needs this:

```
  +-- RELIABILITY -------------------------------------------+
  |  [ ] DLQ configured on EVERY subscription                |
  |  [ ] maxDeliveryAttempts set (5-10 recommended)          |
  |  [ ] Exponential backoff configured                      |
  |  [ ] Message retention configured (default 7 days)       |
  |  [ ] Idempotent subscribers (check txnId before process) |
  +----------------------------------------------------------+

  +-- MONITORING --------------------------------------------+
  |  [ ] Alert: unacked messages > threshold                  |
  |  [ ] Alert: oldest unacked age > ack deadline             |
  |  [ ] Alert: DLQ depth > 0                                 |
  |  [ ] Dashboard: publish rate vs ack rate                  |
  |  [ ] Structured logging with messageId + transactionId   |
  +----------------------------------------------------------+

  +-- SECURITY ----------------------------------------------+
  |  [ ] Dedicated service accounts (not default compute SA) |
  |  [ ] Least privilege: publisher gets pubsub.publisher    |
  |  [ ] Least privilege: subscriber gets pubsub.subscriber  |
  |  [ ] No allow-unauthenticated on internal services       |
  +----------------------------------------------------------+

  +-- PERFORMANCE -------------------------------------------+
  |  [ ] Ack deadline matches processing time                |
  |  [ ] Ordering keys only where needed (costs throughput)  |
  |  [ ] Filters configured to reduce unnecessary delivery   |
  |  [ ] Subscriber auto-scales based on backlog             |
  +----------------------------------------------------------+

  +-- OPERATIONAL -------------------------------------------+
  |  [ ] Runbook: "What to do when DLQ has messages"         |
  |  [ ] Runbook: "What to do when backlog is growing"       |
  |  [ ] Message schema documented and versioned             |
  |  [ ] DLQ reviewed daily (or automated alerting)          |
  +----------------------------------------------------------+
```

---

## The UPI Transfer Service — Complete Production Architecture

```
                                        +-- Cloud Monitoring --+
                                        |  Alerts & Dashboards |
                                        +----------+-----------+
                                                   |
  User                                             | monitors
   |                                               |
   | POST /api/v1/transfer                         |
   v                                               v
  +-- Cloud Run ---------+    +----[ transaction-events TOPIC ]----+
  | upi-transfer-service |    |                                     |
  |   @Profile("pubsub") |--->|  +-- notif-sub -------> Cloud Run  |
  |   Publisher           |    |  |   (no filter)         notification-service
  +------^----------------+    |  |   DLQ: 5 attempts     |
         |                     |  |                        +-> SMS to sender
         |                     |  |                        +-> SMS to receiver
    Cloud Run URL              |  |
    (auto-scaled)              |  +-- fraud-sub -------> Cloud Run
                               |  |   filter: HIGH        fraud-detection-service
                               |  |   DLQ: 5 attempts     |
                               |  |                        +-> Flag for review
                               |  |                        +-> Rapid-fire detection
                               |  |
                               |  +-- analytics-sub ---> Cloud Run
                               |      filter: SUCCESS     analytics-service
                               |      DLQ: 5 attempts     |
                               |                           +-> Record metrics
                               +------+--------------------+
                                      |
                                      | (failed after 5 retries)
                                      v
                               +-- transaction-events-dlq --+
                               |   dlq-monitor-sub          |
                               |   Alert: depth > 0         |
                               +----------------------------+
```

---

## What We Covered in 2 Days

### Day 37 — Foundations
| Topic                  | What You Did                                          |
|------------------------|-------------------------------------------------------|
| Pub/Sub concepts       | Topics, subscriptions, messages, ack/nack             |
| Fan-out pattern        | 1 publisher, 3 subscribers                            |
| Local emulator         | Full event-driven system on your laptop               |
| Four factors           | Measured throughput, latency, durability, availability |
| Message attributes     | eventType, status, senderUpiId, amountRange           |

### Day 38 — Production Patterns
| Topic                  | What You Did                                          |
|------------------------|-------------------------------------------------------|
| Dead Letter Queues     | Handling poison messages, DLQ monitoring               |
| Message ordering       | Ordering keys, trade-offs, stall problem              |
| Server-side filtering  | Deliver only what each subscriber needs               |
| Retry & idempotency    | Backoff, ack deadlines, exactly-once, idempotent code |
| Cloud Run deployment   | 4 services live on GCP, real Pub/Sub                  |
| Production monitoring  | Key metrics, alerts, structured logging               |
| Comparison             | Pub/Sub vs Kafka vs RabbitMQ decision framework       |
| Production checklist   | Reliability, monitoring, security, performance        |

---

## The Four Factors — Proven in Production

```
  +-- THROUGHPUT ---+    +-- LATENCY ------+
  |                 |    |                  |
  |  100+ msg/sec   |    |  ~85ms e2e      |
  |  all subscribers |    |  publish to     |
  |  keeping up     |    |  subscriber ack |
  |                 |    |                  |
  +-----------------+    +------------------+

  +-- DURABILITY ---+    +-- AVAILABILITY --+
  |                 |    |                  |
  |  Messages       |    |  Publisher works |
  |  retained 7     |    |  even when all   |
  |  days. Survive   |    |  subscribers are |
  |  subscriber     |    |  down. Zero      |
  |  restarts.      |    |  coupling.       |
  |                 |    |                  |
  +-----------------+    +------------------+
```

> You didn't just learn Pub/Sub. You built, deployed, and operated a production-grade event-driven architecture on GCP.

---

## Key Takeaways

1. **DLQ is not optional.** Every subscription needs one. Monitor it daily.

2. **Ordering costs throughput.** Use it only when correctness depends on sequence.

3. **Filter at the source.** Design message attributes so subscribers can filter server-side.

4. **Idempotency is non-negotiable.** Even with exactly-once delivery, make your subscribers idempotent.

5. **Monitor the three signals:** unacked messages, oldest unacked age, DLQ depth.

6. **Same code, different config.** Spring Cloud GCP makes local-to-cloud seamless.

7. **Start with Pub/Sub.** Graduate to Kafka only when you need replay or extreme throughput.

> "The best messaging system is the one you don't have to operate." -- Every SRE ever

---

## What's Next

```
  Day 37:  Pub/Sub Foundations (local emulator)        -- DONE
  Day 38:  Production Patterns + Deploy to GCP         -- DONE (today)
  -------
  Next:    CI/CD Pipeline — automate everything
           GKE — when Cloud Run isn't enough
           Monitoring & Observability deep dive
```

### You now have:
- A **real microservices architecture** running on GCP
- **Event-driven communication** with production-grade patterns
- **Zero coupling** between services
- **Auto-scaling** on Cloud Run
- **Monitoring and alerting** for production operations

> The UPI Transfer Service is no longer a demo. It's a production-ready, cloud-native application.
