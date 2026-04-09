# Lab: Production Pub/Sub Patterns + Deploy to GCP Cloud Run

**Day 38 | Ford Phase 6 — Cloud & DevOps Training**

| Detail           | Value                                                |
|------------------|------------------------------------------------------|
| Duration         | ~3 hours hands-on                                    |
| Prerequisites    | Day 37 lab complete (4 microservices running locally) |
| Services         | upi-transfer-service, notification, fraud, analytics |
| GCP Services     | Pub/Sub, Cloud Run, Artifact Registry, Cloud Build   |
| Difficulty       | Intermediate to Advanced                             |

---

## Architecture Recap

```
                            ┌─────────────────────────┐
                            │  transaction-events      │
                            │  (Pub/Sub Topic)         │
                            └────┬────────┬────────┬───┘
                                 │        │        │
                    ┌────────────┘        │        └────────────┐
                    ▼                     ▼                     ▼
          ┌─────────────────┐  ┌──────────────────┐  ┌─────────────────┐
          │ notification-sub│  │fraud-detection-sub│  │  analytics-sub  │
          └────────┬────────┘  └────────┬─────────┘  └────────┬────────┘
                   ▼                    ▼                     ▼
          ┌─────────────────┐  ┌──────────────────┐  ┌─────────────────┐
          │ notification-   │  │ fraud-detection-  │  │   analytics-    │
          │ service (:8081) │  │ service (:8082)   │  │   service(:8083)│
          └────────┬────────┘  └──────────────────┘  └─────────────────┘
                   │ (on failure)
                   ▼
          ┌─────────────────────────┐
          │ transaction-events-dlq  │
          │ (Dead Letter Queue)     │
          └─────────────────────────┘
```

**Publisher:** upi-transfer-service (port 8080, requires `--spring.profiles.active=pubsub`)

---

## Before You Begin

Make sure your Day 37 setup is still running:
- Pub/Sub emulator on port 8085
- All 4 microservices running and healthy

If not, start them up again:

```bash
# Terminal 1: Start the emulator
gcloud beta emulators pubsub start --project=test-project --host-port=localhost:8085

# Terminal 2: Set emulator env and start publisher
export PUBSUB_EMULATOR_HOST=localhost:8085
cd ~/ford-phase6/upi-transfer-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=pubsub

# Terminal 3: Start notification-service
export PUBSUB_EMULATOR_HOST=localhost:8085
cd ~/ford-phase6/notification-service
./mvnw spring-boot:run

# Terminal 4: Start fraud-detection-service
export PUBSUB_EMULATOR_HOST=localhost:8085
cd ~/ford-phase6/fraud-detection-service
./mvnw spring-boot:run

# Terminal 5: Start analytics-service
export PUBSUB_EMULATOR_HOST=localhost:8085
cd ~/ford-phase6/analytics-service
./mvnw spring-boot:run
```

Quick health check:

```bash
curl -s http://localhost:8080/actuator/health | jq
curl -s http://localhost:8081/actuator/health | jq
curl -s http://localhost:8082/actuator/health | jq
curl -s http://localhost:8083/actuator/health | jq
```

All should return `{"status":"UP"}`.

---

## Part 1: Dead Letter Queue (DLQ) in Action (30 min)

### 1.1 What Is a Dead Letter Queue?

A **Dead Letter Queue** (DLQ) is a special topic where messages go when they
cannot be processed successfully after multiple attempts.

**Why do we need it?**
- A subscriber might crash, throw an exception, or time out
- Without a DLQ, the message keeps getting redelivered forever (infinite retry loop)
- With a DLQ, after N failed attempts, Pub/Sub moves the message to the DLQ topic
- An operator can inspect DLQ messages later, fix the bug, and replay them

**Real-world scenario:** A notification-service fails to send SMS because the
telecom API is down. After 5 retries, the message lands in the DLQ. When the
telecom API recovers, an operator replays the DLQ messages.

### 1.2 Simulate a Processing Failure

We will temporarily break the notification-service to simulate a failure.

**Step 1:** Stop the notification-service (Ctrl+C in Terminal 3).

**Step 2:** Open the `NotificationSubscriber.java` file:

```
notification-service/src/main/java/com/example/notification/NotificationSubscriber.java
```

**Step 3:** Add a failure condition. Find this block (around line 52):

```java
                TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
```

Add these 4 lines **immediately after** that line:

```java
                // TEMPORARY: Simulate failure for high-amount transactions
                if (event.getAmount().doubleValue() > 7000) {
                    throw new RuntimeException("SIMULATED FAILURE: Cannot process amount > ₹7000");
                }
```

The full block should now look like this:

```java
            try {
                TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);

                // TEMPORARY: Simulate failure for high-amount transactions
                if (event.getAmount().doubleValue() > 7000) {
                    throw new RuntimeException("SIMULATED FAILURE: Cannot process amount > ₹7000");
                }

                // Calculate latency (publish → receive)
                long publishTime = message.getPubsubMessage().getPublishTime().getSeconds() * 1000;
                // ... rest of the code
```

**Step 4:** Restart the notification-service:

```bash
export PUBSUB_EMULATOR_HOST=localhost:8085
cd ~/ford-phase6/notification-service
./mvnw spring-boot:run
```

### 1.3 Test the Failure

**Send a transfer for ₹8000:**

```bash
curl -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "ram@upi",
    "amount": 8000,
    "remark": "DLQ test - high amount"
  }'
```

**Expected response:**

```json
{
  "transactionId": "TXN-...",
  "status": "SUCCESS",
  "message": "Transfer of ₹8000 from nag@upi to ram@upi completed"
}
```

### 1.4 Observe the Logs

**Publisher (Terminal 2) -- publishes normally:**

```
PUBLISHED | topic=transaction-events | txnId=TXN-... | amount=8000
```

**Notification service (Terminal 3) -- fails repeatedly:**

```
Failed to process message: msg-001 | Error: SIMULATED FAILURE: Cannot process amount > ₹7000
Failed to process message: msg-001 | Error: SIMULATED FAILURE: Cannot process amount > ₹7000
Failed to process message: msg-001 | Error: SIMULATED FAILURE: Cannot process amount > ₹7000
... (keeps repeating!)
```

**Fraud-detection service (Terminal 4) -- processes normally:**

```
FRAUD CHECK | TxnId: TXN-... | nag@upi → ram@upi | ₹8000
ALERT: HIGH VALUE TRANSACTION! ₹8000 exceeds threshold ₹5000
```

**Analytics service (Terminal 5) -- processes normally:**

```
ANALYTICS | TxnId: TXN-... | ₹8000 | nag@upi → ram@upi
```

> **Key Observation:** The message is nacked by notification-service, so Pub/Sub
> keeps redelivering it. This is the "poison pill" problem. The other subscribers
> are unaffected because each subscription is independent.

### 1.5 Send a Normal Transfer (Verify Low Amounts Still Work)

```bash
curl -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "sita@upi",
    "amount": 500,
    "remark": "DLQ test - low amount"
  }'
```

**Expected:** Notification-service processes this one successfully (amount <= 7000).

### 1.6 How DLQ Solves This on Real GCP

On the real GCP Pub/Sub (not the emulator), you configure DLQ like this:

```bash
# Create the DLQ topic
gcloud pubsub topics create transaction-events-dlq

# Create a subscription to monitor DLQ messages
gcloud pubsub subscriptions create dlq-monitor-sub \
  --topic=transaction-events-dlq

# Attach DLQ to notification-sub with max 5 delivery attempts
gcloud pubsub subscriptions update notification-sub \
  --dead-letter-topic=transaction-events-dlq \
  --max-delivery-attempts=5
```

**What happens on real GCP:**
1. Message arrives at notification-sub
2. Notification-service throws exception, nacks the message
3. Pub/Sub redelivers: attempt 2... nack... attempt 3... nack...
4. After attempt 5: Pub/Sub moves the message to `transaction-events-dlq`
5. The message stops being redelivered to notification-sub
6. An operator reads the DLQ, investigates, and fixes the issue

> **Note:** The emulator does NOT support DLQ routing. That is why the message
> keeps looping forever in our test. On real GCP, it stops after 5 attempts.

### 1.7 Revert the Code Change

**This step is important!** Remove the simulated failure code.

Open `NotificationSubscriber.java` and **delete** these 4 lines:

```java
                // TEMPORARY: Simulate failure for high-amount transactions
                if (event.getAmount().doubleValue() > 7000) {
                    throw new RuntimeException("SIMULATED FAILURE: Cannot process amount > ₹7000");
                }
```

Restart notification-service:

```bash
cd ~/ford-phase6/notification-service
./mvnw spring-boot:run
```

Verify it works again:

```bash
curl -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "ram@upi",
    "amount": 9000,
    "remark": "After DLQ fix"
  }'
```

**Expected:** Notification-service processes ₹9000 successfully now.

---

## Part 2: Message Ordering Demo (20 min)

### 2.1 What Are Ordering Keys?

By default, Pub/Sub does **not** guarantee message order. Messages may arrive
in any order at the subscriber.

**Ordering keys** let you say: "All messages with the same key must arrive
in the order they were published."

**UPI example:** All transactions from `nag@upi` should be processed in order
(so the balance check, fraud check, and notifications happen sequentially).

### 2.2 Modify the Publisher to Use Ordering Keys

Open `TransactionEventPublisher.java`:

```
upi-transfer-service/src/main/java/com/example/upi/service/TransactionEventPublisher.java
```

**Current code (line 47):**

```java
            pubSubTemplate.publish(topicName, json, attributes)
```

**Replace with (adds ordering key = senderUpiId):**

```java
            // Use senderUpiId as ordering key — all transfers from the same
            // sender are delivered in order
            String orderingKey = event.getSenderUpiId();
            log.info("Publishing with ordering key: {}", orderingKey);

            pubSubTemplate.publish(topicName, json, attributes)
```

> **Note:** Full ordering support requires enabling ordering on the
> `PubSubPublisherTemplate` and using ordered subscriptions. In the emulator,
> messages from a single publisher already arrive roughly in order since there
> is only one connection. On real GCP, you would configure:
>
> ```java
> @Bean
> public PubSubPublisherTemplate pubSubPublisherTemplate(PubSubPublisherFactory factory) {
>     PubSubPublisherTemplate template = new PubSubPublisherTemplate(factory);
>     template.setEnableMessageOrdering(true);
>     return template;
> }
> ```
>
> And create an ordered subscription:
> ```bash
> gcloud pubsub subscriptions create notification-sub \
>   --topic=transaction-events \
>   --enable-message-ordering
> ```

### 2.3 Test Ordering with Rapid Transfers

Restart the publisher after the code change:

```bash
export PUBSUB_EMULATOR_HOST=localhost:8085
cd ~/ford-phase6/upi-transfer-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=pubsub
```

**Send 5 rapid transfers from the SAME sender:**

```bash
for i in $(seq 1 5); do
  curl -s -X POST http://localhost:8080/api/transfer \
    -H "Content-Type: application/json" \
    -d "{
      \"senderUpiId\": \"nag@upi\",
      \"receiverUpiId\": \"ram@upi\",
      \"amount\": ${i}00,
      \"remark\": \"Order test $i\"
    }" &
done
wait
echo "All 5 transfers sent!"
```

**Check subscriber logs (any terminal):**

The transaction IDs should appear in sequential order:

```
TXN-00001 | ₹100
TXN-00002 | ₹200
TXN-00003 | ₹300
TXN-00004 | ₹400
TXN-00005 | ₹500
```

### 2.4 Test with Two Different Senders

```bash
# Sender 1: nag@upi
for i in $(seq 1 3); do
  curl -s -X POST http://localhost:8080/api/transfer \
    -H "Content-Type: application/json" \
    -d "{
      \"senderUpiId\": \"nag@upi\",
      \"receiverUpiId\": \"ram@upi\",
      \"amount\": ${i}000,
      \"remark\": \"Nag order $i\"
    }" &
done

# Sender 2: sita@upi
for i in $(seq 1 3); do
  curl -s -X POST http://localhost:8080/api/transfer \
    -H "Content-Type: application/json" \
    -d "{
      \"senderUpiId\": \"sita@upi\",
      \"receiverUpiId\": \"ram@upi\",
      \"amount\": ${i}00,
      \"remark\": \"Sita order $i\"
    }" &
done
wait
echo "All 6 transfers sent!"
```

**Expected behavior:**
- All messages from `nag@upi` arrive in order (₹1000, ₹2000, ₹3000)
- All messages from `sita@upi` arrive in order (₹100, ₹200, ₹300)
- Messages from nag and sita may interleave (ordering is per-key, not global)

### 2.5 Ordering Trade-Off

| Aspect          | Without Ordering     | With Ordering          |
|-----------------|---------------------|------------------------|
| Parallelism     | Full parallel        | Serialized per key     |
| Throughput      | Higher               | Lower (per key)        |
| Use case        | Independent events   | Related events in seq  |
| Configuration   | Default              | Needs explicit setup   |

> **Rule of thumb:** Only use ordering keys when you truly need sequential
> processing. Most event-driven systems work fine without ordering.

**Revert the code change** in `TransactionEventPublisher.java` (remove the
ordering key log line) and restart the publisher.

---

## Part 3: Server-Side Filtering (20 min)

### 3.1 What Is Server-Side Filtering?

Normally, a subscriber receives ALL messages from a topic. With server-side
filtering, Pub/Sub itself filters messages before delivering them.

**Example:** The fraud-detection-service only cares about HIGH-amount
transactions. Instead of receiving all messages and discarding low ones, we
can tell Pub/Sub: "Only deliver messages where `amountRange = HIGH`."

### 3.2 Our Publisher Already Sets Attributes

Look at `TransactionEventPublisher.java` (lines 39-45):

```java
Map<String, String> attributes = Map.of(
        "eventType", "TRANSFER",
        "status", event.getStatus(),
        "senderUpiId", event.getSenderUpiId(),
        "receiverUpiId", event.getReceiverUpiId(),
        "amountRange", categorizeAmount(event.getAmount().doubleValue())
);
```

The `categorizeAmount` method (lines 64-68):

```java
private String categorizeAmount(double amount) {
    if (amount >= 10000) return "HIGH";
    if (amount >= 1000) return "MEDIUM";
    return "LOW";
}
```

So every published message has an `amountRange` attribute: LOW, MEDIUM, or HIGH.

### 3.3 Server-Side Filter with gcloud (Real GCP Only)

On real GCP, you would create a filtered subscription:

```bash
# Create a subscription that ONLY receives HIGH-amount messages
gcloud pubsub subscriptions create fraud-high-only-sub \
  --topic=transaction-events \
  --filter='attributes.amountRange="HIGH"'
```

Now a subscriber pulling from `fraud-high-only-sub` would ONLY receive
messages where `amountRange` is `HIGH` (amount >= ₹10,000).

**Other filter examples:**

```bash
# Only SUCCESS transactions
--filter='attributes.status="SUCCESS"'

# Only transactions from a specific sender
--filter='attributes.senderUpiId="nag@upi"'

# Combine filters (AND logic)
--filter='attributes.amountRange="HIGH" AND attributes.status="SUCCESS"'
```

> **Note:** The emulator has limited filter support. These commands work only
> on real GCP Pub/Sub.

### 3.4 Application-Level Filtering (Works Everywhere)

Our fraud-detection-service already does application-level filtering.

Look at `FraudDetectionSubscriber.java` (lines 87-93):

```java
// ---- Rule 1: High amount detection ----
boolean isHighAmount = event.getAmount().compareTo(highAmountThreshold) >= 0;
if (isHighAmount) {
    alertCount.incrementAndGet();
    log.warn("  ALERT: HIGH VALUE TRANSACTION! ₹{} exceeds threshold ₹{}",
            event.getAmount(), highAmountThreshold);
    log.warn("  Action: Flagged for manual review");
}
```

This receives ALL messages but only alerts on high amounts. It still
acknowledges and discards the rest.

### 3.5 Test Application-Level Filtering

```bash
# LOW amount (< ₹1000)
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":500,"remark":"Low amount"}'

# MEDIUM amount (₹1000 - ₹9999)
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":3000,"remark":"Medium amount"}'

# HIGH amount (>= ₹10000)
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":15000,"remark":"High amount"}'
```

**Check fraud-detection-service logs:**

```
FRAUD CHECK | TxnId: ... | ₹500     → Result: CLEAN — no fraud indicators
FRAUD CHECK | TxnId: ... | ₹3000    → Result: CLEAN — no fraud indicators
FRAUD CHECK | TxnId: ... | ₹15000   → ALERT: HIGH VALUE TRANSACTION!
```

All 3 messages are received and processed. The fraud alert only fires for HIGH.

### 3.6 Comparison: Server-Side vs Application-Level Filtering

| Factor                | Server-Side (GCP Filter)           | Application-Level            |
|-----------------------|------------------------------------|------------------------------|
| Where filtering runs  | On Pub/Sub infrastructure          | In your application code     |
| Network cost          | Filtered messages not delivered     | All messages delivered        |
| Processing cost       | Lower (fewer messages to process)  | Higher (process then discard) |
| Flexibility           | Simple attribute matching only     | Any complex logic             |
| Works on emulator?    | No                                 | Yes                          |
| Latency               | Lower                              | Slightly higher              |
| Cost savings           | Significant at scale              | None                         |
| Use when...            | High volume, simple criteria      | Complex rules, low volume    |

> **Production recommendation:** Use server-side filtering for high-volume topics
> where subscribers only need a subset. Use application-level filtering for
> complex business logic (like the rapid-fire detection in our fraud service).

---

## Part 4: Deploy to Real GCP Cloud Run (60 min)

Now the big moment -- we deploy everything to Google Cloud. No more emulator.
Real Pub/Sub. Real Cloud Run. Real production infrastructure.

### Step 1: Create GCP Resources (15 min)

**Set your project ID:**

```bash
# Replace with your actual project ID
export PROJECT_ID=your-project-id
gcloud config set project $PROJECT_ID
```

Verify:

```bash
gcloud config get-value project
```

**Expected output:**

```
your-project-id
```

**Enable required APIs:**

```bash
gcloud services enable \
  pubsub.googleapis.com \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com
```

**Expected output (for each API):**

```
Operation "operations/acf.p2-...-..." finished successfully.
```

> This may take 1-2 minutes. If an API is already enabled, it will say so.

**Create the Pub/Sub topic:**

```bash
gcloud pubsub topics create transaction-events
```

**Expected output:**

```
Created topic [projects/your-project-id/topics/transaction-events].
```

**Create the 3 fan-out subscriptions:**

```bash
gcloud pubsub subscriptions create notification-sub \
  --topic=transaction-events \
  --ack-deadline=30

gcloud pubsub subscriptions create fraud-detection-sub \
  --topic=transaction-events \
  --ack-deadline=30

gcloud pubsub subscriptions create analytics-sub \
  --topic=transaction-events \
  --ack-deadline=30
```

**Expected output (for each):**

```
Created subscription [projects/your-project-id/subscriptions/notification-sub].
Created subscription [projects/your-project-id/subscriptions/fraud-detection-sub].
Created subscription [projects/your-project-id/subscriptions/analytics-sub].
```

**Create the Dead Letter Queue:**

```bash
# DLQ topic
gcloud pubsub topics create transaction-events-dlq

# Subscription to monitor DLQ messages
gcloud pubsub subscriptions create dlq-monitor-sub \
  --topic=transaction-events-dlq

# Attach DLQ to notification-sub
gcloud pubsub subscriptions update notification-sub \
  --dead-letter-topic=transaction-events-dlq \
  --max-delivery-attempts=5
```

**Expected output:**

```
Created topic [projects/your-project-id/topics/transaction-events-dlq].
Created subscription [projects/your-project-id/subscriptions/dlq-monitor-sub].
Updated subscription [projects/your-project-id/subscriptions/notification-sub].
```

**Create Artifact Registry repository:**

```bash
gcloud artifacts repositories create upi-services \
  --repository-format=docker \
  --location=asia-south1 \
  --description="Docker images for UPI microservices"
```

**Expected output:**

```
Create request issued for: [upi-services]
Created repository [upi-services].
```

**Verify everything was created:**

```bash
echo "=== Topics ==="
gcloud pubsub topics list

echo ""
echo "=== Subscriptions ==="
gcloud pubsub subscriptions list --format="table(name, topic, deadLetterPolicy)"

echo ""
echo "=== Artifact Registry ==="
gcloud artifacts repositories list --location=asia-south1
```

---

### Step 2: Build and Push Docker Images (15 min)

**Configure Docker authentication for Artifact Registry:**

```bash
gcloud auth configure-docker asia-south1-docker.pkg.dev
```

When prompted, type `Y` and press Enter.

**Build and push all 4 services:**

```bash
cd ~/ford-phase6

# Build and push upi-transfer-service
echo "========================================="
echo "Building upi-transfer-service..."
echo "========================================="
cd upi-transfer-service
docker build -t asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/upi-transfer-service:v1 .
docker push asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/upi-transfer-service:v1
cd ..

# Build and push notification-service
echo "========================================="
echo "Building notification-service..."
echo "========================================="
cd notification-service
docker build -t asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/notification-service:v1 .
docker push asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/notification-service:v1
cd ..

# Build and push fraud-detection-service
echo "========================================="
echo "Building fraud-detection-service..."
echo "========================================="
cd fraud-detection-service
docker build -t asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/fraud-detection-service:v1 .
docker push asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/fraud-detection-service:v1
cd ..

# Build and push analytics-service
echo "========================================="
echo "Building analytics-service..."
echo "========================================="
cd analytics-service
docker build -t asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/analytics-service:v1 .
docker push asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/analytics-service:v1
cd ..
```

> **Tip:** Each build takes 2-3 minutes (Maven downloads dependencies inside
> the Docker build). The push takes 30-60 seconds per image.

**Verify images are in Artifact Registry:**

```bash
gcloud artifacts docker images list \
  asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services \
  --format="table(package, version)"
```

**Expected output:**

```
PACKAGE                                                                          VERSION
asia-south1-docker.pkg.dev/your-project-id/upi-services/analytics-service        v1
asia-south1-docker.pkg.dev/your-project-id/upi-services/fraud-detection-service  v1
asia-south1-docker.pkg.dev/your-project-id/upi-services/notification-service     v1
asia-south1-docker.pkg.dev/your-project-id/upi-services/upi-transfer-service     v1
```

---

### Step 3: Deploy Publisher (upi-transfer-service) to Cloud Run (10 min)

```bash
gcloud run deploy upi-transfer-service \
  --image=asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/upi-transfer-service:v1 \
  --region=asia-south1 \
  --platform=managed \
  --allow-unauthenticated \
  --set-env-vars="SPRING_PROFILES_ACTIVE=pubsub,GCP_PROJECT_ID=$PROJECT_ID" \
  --memory=512Mi \
  --port=8080
```

**Expected output:**

```
Deploying container to Cloud Run service [upi-transfer-service] in project [your-project-id] region [asia-south1]
✓ Deploying... Done.
  ✓ Creating Revision...
  ✓ Routing traffic...
  ✓ Setting IAM Policy...
Done.
Service [upi-transfer-service] revision [upi-transfer-service-00001-xxx] has been deployed and is serving 100 percent of traffic.
Service URL: https://upi-transfer-service-xxxxx-el.a.run.app
```

> **Key insight:** Notice we did NOT set `PUBSUB_EMULATOR_HOST`. When this
> variable is absent, Spring Cloud GCP automatically connects to the real
> GCP Pub/Sub service. The application code is identical -- only the
> environment changes.

**Save the publisher URL:**

```bash
export PUBLISHER_URL=$(gcloud run services describe upi-transfer-service \
  --region=asia-south1 \
  --format='value(status.url)')

echo "Publisher URL: $PUBLISHER_URL"
```

**Quick test (publisher only, subscribers not deployed yet):**

```bash
curl -s "$PUBLISHER_URL/actuator/health" | jq
```

**Expected output:**

```json
{
  "status": "UP"
}
```

---

### Step 4: Deploy 3 Subscribers to Cloud Run (15 min)

> **Important:** Cloud Run scales to zero by default. But our subscribers use
> pull-based subscriptions -- they need at least 1 instance running at all times
> to pull messages. We set `--min-instances=1` for each subscriber.

**Deploy notification-service:**

```bash
gcloud run deploy notification-service \
  --image=asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/notification-service:v1 \
  --region=asia-south1 \
  --platform=managed \
  --allow-unauthenticated \
  --set-env-vars="GCP_PROJECT_ID=$PROJECT_ID" \
  --memory=512Mi \
  --port=8081 \
  --min-instances=1
```

**Deploy fraud-detection-service:**

```bash
gcloud run deploy fraud-detection-service \
  --image=asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/fraud-detection-service:v1 \
  --region=asia-south1 \
  --platform=managed \
  --allow-unauthenticated \
  --set-env-vars="GCP_PROJECT_ID=$PROJECT_ID" \
  --memory=512Mi \
  --port=8082 \
  --min-instances=1
```

**Deploy analytics-service:**

```bash
gcloud run deploy analytics-service \
  --image=asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services/analytics-service:v1 \
  --region=asia-south1 \
  --platform=managed \
  --allow-unauthenticated \
  --set-env-vars="GCP_PROJECT_ID=$PROJECT_ID" \
  --memory=512Mi \
  --port=8083 \
  --min-instances=1
```

**Verify all 4 services are running:**

```bash
gcloud run services list --region=asia-south1 \
  --format="table(SERVICE, REGION, URL, LAST_DEPLOYED_BY)"
```

**Expected output:**

```
SERVICE                  REGION       URL                                                         LAST DEPLOYED BY
analytics-service        asia-south1  https://analytics-service-xxxxx-el.a.run.app                you@example.com
fraud-detection-service  asia-south1  https://fraud-detection-service-xxxxx-el.a.run.app          you@example.com
notification-service     asia-south1  https://notification-service-xxxxx-el.a.run.app             you@example.com
upi-transfer-service     asia-south1  https://upi-transfer-service-xxxxx-el.a.run.app             you@example.com
```

---

### Step 5: End-to-End Test on Cloud Run (5 min)

**Make a transfer through the real GCP infrastructure:**

```bash
PUBLISHER_URL=$(gcloud run services describe upi-transfer-service \
  --region=asia-south1 --format='value(status.url)')

curl -X POST "$PUBLISHER_URL/api/transfer" \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "ram@upi",
    "amount": 2500,
    "remark": "Cloud Run test - first real GCP transfer!"
  }'
```

**Expected response:**

```json
{
  "transactionId": "TXN-00001",
  "status": "SUCCESS",
  "message": "Transfer of ₹2500 from nag@upi to ram@upi completed",
  "timestamp": "2026-04-08T..."
}
```

**Check logs of all 4 services:**

```bash
echo "=== Publisher Logs ==="
gcloud run services logs read upi-transfer-service --region=asia-south1 --limit=10

echo ""
echo "=== Notification Service Logs ==="
gcloud run services logs read notification-service --region=asia-south1 --limit=10

echo ""
echo "=== Fraud Detection Service Logs ==="
gcloud run services logs read fraud-detection-service --region=asia-south1 --limit=10

echo ""
echo "=== Analytics Service Logs ==="
gcloud run services logs read analytics-service --region=asia-south1 --limit=10
```

**Expected in publisher logs:**

```
PUBLISHED | topic=transaction-events | txnId=TXN-00001 | amount=2500
```

**Expected in notification-service logs:**

```
NOTIFICATION SENT!
SMS to nag@upi: You sent ₹2500 to ram@upi
SMS to ram@upi: You received ₹2500 from nag@upi
```

**Expected in fraud-detection-service logs:**

```
FRAUD CHECK | TxnId: TXN-00001 | nag@upi → ram@upi | ₹2500
Result: CLEAN — no fraud indicators
```

**Expected in analytics-service logs:**

```
ANALYTICS | TxnId: TXN-00001 | ₹2500 | nag@upi → ram@upi
```

> You can also view logs in the Cloud Console: go to **Cloud Run** > click a
> service > **Logs** tab. This gives a nicer UI with filtering and search.

**Send a few more transfers to build up data:**

```bash
curl -s -X POST "$PUBLISHER_URL/api/transfer" \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"sita@upi","receiverUpiId":"gita@upi","amount":1200,"remark":"Rent"}' &

curl -s -X POST "$PUBLISHER_URL/api/transfer" \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"raj@upi","receiverUpiId":"nag@upi","amount":50000,"remark":"Car payment"}' &

curl -s -X POST "$PUBLISHER_URL/api/transfer" \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":150,"remark":"Tea"}' &

wait
echo "3 transfers sent!"
```

---

## Part 5: Monitor Pub/Sub on GCP Console (30 min)

### 5.1 Topic Monitoring

1. Open the **GCP Console**: https://console.cloud.google.com
2. Navigate to **Pub/Sub** > **Topics**
3. Click on **transaction-events**
4. Click the **Monitoring** tab

**What you will see:**
- **Publish message rate:** A graph showing messages published per second
- **Publish message size:** Average message size in bytes

### 5.2 Subscription Monitoring

1. Navigate to **Pub/Sub** > **Subscriptions**
2. Click on **notification-sub**
3. Click the **Monitoring** tab

**Key metrics to observe:**
- **Unacked message count:** Messages delivered but not yet acknowledged
- **Ack message rate:** Messages acknowledged per second
- **Oldest unacked message age:** How long the oldest unacked message has been waiting
- **Push/Pull request count:** Number of pull requests from the subscriber

### 5.3 Generate Traffic for Monitoring

Let us make 10 rapid transfers to see the metrics update:

```bash
PUBLISHER_URL=$(gcloud run services describe upi-transfer-service \
  --region=asia-south1 --format='value(status.url)')

for i in $(seq 1 10); do
  curl -s -X POST "$PUBLISHER_URL/api/transfer" \
    -H "Content-Type: application/json" \
    -d "{
      \"senderUpiId\": \"load-test@upi\",
      \"receiverUpiId\": \"receiver@upi\",
      \"amount\": ${i}00,
      \"remark\": \"Load test $i\"
    }" &
done
wait
echo "10 transfers sent!"
```

Go back to the Console monitoring tab and watch the graphs update (may take
30-60 seconds for metrics to appear).

### 5.4 Check Subscriber Stats

If your services expose a `/stats` endpoint, check them:

```bash
NOTIF_URL=$(gcloud run services describe notification-service \
  --region=asia-south1 --format='value(status.url)')
FRAUD_URL=$(gcloud run services describe fraud-detection-service \
  --region=asia-south1 --format='value(status.url)')
ANALYTICS_URL=$(gcloud run services describe analytics-service \
  --region=asia-south1 --format='value(status.url)')

echo "=== Notification Stats ==="
curl -s "$NOTIF_URL/stats" | jq

echo ""
echo "=== Fraud Detection Stats ==="
curl -s "$FRAUD_URL/stats" | jq

echo ""
echo "=== Analytics Stats ==="
curl -s "$ANALYTICS_URL/stats" | jq
```

### 5.5 Compare Latency: Local Emulator vs Real GCP

| Metric              | Local Emulator   | Real GCP Cloud Run     |
|---------------------|------------------|------------------------|
| Publish-to-receive  | 1-5 ms           | 50-200 ms              |
| Network hops        | localhost only    | Publisher -> Pub/Sub -> Subscriber |
| Consistency         | Very consistent   | Slight variation       |

> **Why the difference?** On real GCP, the message travels over the network
> from Cloud Run to Pub/Sub servers, gets persisted, then delivered to the
> subscriber's pull request. On localhost, everything is in-memory.

### 5.6 Set Up a Monitoring Alert (Optional)

**Via gcloud:**

```bash
# Create an alert policy for undelivered messages > 100
gcloud alpha monitoring policies create \
  --display-name="High Undelivered Messages" \
  --condition-display-name="notification-sub backlog" \
  --condition-filter='resource.type="pubsub_subscription" AND resource.labels.subscription_id="notification-sub" AND metric.type="pubsub.googleapis.com/subscription/num_undelivered_messages"' \
  --condition-threshold-value=100 \
  --condition-threshold-duration=300s \
  --condition-threshold-comparison=COMPARISON_GT \
  --notification-channels="" \
  --combiner=OR
```

**Via Console (easier for beginners):**

1. Navigate to **Monitoring** > **Alerting**
2. Click **Create Policy**
3. Add condition:
   - Resource type: **Cloud Pub/Sub Subscription**
   - Metric: **Undelivered messages**
   - Filter: `subscription_id = notification-sub`
   - Threshold: `> 100`
   - Duration: `5 minutes`
4. Skip notification channel for now (or add your email)
5. Name the policy: "High Undelivered Messages - Notification Sub"
6. Click **Create Policy**

---

## Part 6: Durability Test on Cloud Run (15 min)

This is the most powerful demo of Pub/Sub's durability guarantee.

### 6.1 Stop the Notification Service

Scale notification-service to zero instances (simulates a crash or deployment):

```bash
gcloud run services update notification-service \
  --region=asia-south1 \
  --max-instances=0
```

**Expected output:**

```
✓ Deploying... Done.
Service [notification-service] revision [...] has been deployed...
```

**Verify it is scaled to zero:**

```bash
gcloud run services describe notification-service \
  --region=asia-south1 \
  --format="value(spec.template.spec.containerConcurrency)"
```

### 6.2 Send 5 Transfers While Notification Service Is Down

```bash
PUBLISHER_URL=$(gcloud run services describe upi-transfer-service \
  --region=asia-south1 --format='value(status.url)')

for i in $(seq 1 5); do
  curl -s -X POST "$PUBLISHER_URL/api/transfer" \
    -H "Content-Type: application/json" \
    -d "{
      \"senderUpiId\": \"durability-test@upi\",
      \"receiverUpiId\": \"receiver@upi\",
      \"amount\": ${i}000,
      \"remark\": \"Durability test $i - notification service is DOWN\"
    }"
  echo ""
done
```

**Expected:** All 5 transfers return SUCCESS. The publisher does not know or
care that a subscriber is down.

### 6.3 Check What Happened

**Fraud-detection and analytics processed normally:**

```bash
gcloud run services logs read fraud-detection-service --region=asia-south1 --limit=10
gcloud run services logs read analytics-service --region=asia-south1 --limit=10
```

**Check unacked messages on notification-sub:**

```bash
gcloud pubsub subscriptions describe notification-sub \
  --format="yaml(name, ackDeadlineSeconds, deadLetterPolicy)"
```

**In the GCP Console:**
1. Go to **Pub/Sub** > **Subscriptions** > **notification-sub**
2. Look at the **Monitoring** tab
3. You should see: **Unacked messages: 5** (or similar count)

> These 5 messages are sitting in Pub/Sub, waiting for notification-service
> to come back and pull them. Pub/Sub retains unacked messages for up to
> **7 days** by default.

### 6.4 Restart Notification Service

```bash
gcloud run services update notification-service \
  --region=asia-south1 \
  --min-instances=1 \
  --max-instances=5
```

**Watch the logs as it starts processing the backlog:**

```bash
# Wait 30 seconds for the service to start, then check logs
sleep 30
gcloud run services logs read notification-service --region=asia-south1 --limit=20
```

**Expected output in logs:**

```
NOTIFICATION SERVICE — subscribing to: notification-sub
NOTIFICATION SENT! SMS to durability-test@upi: You sent ₹1000...
NOTIFICATION SENT! SMS to durability-test@upi: You sent ₹2000...
NOTIFICATION SENT! SMS to durability-test@upi: You sent ₹3000...
NOTIFICATION SENT! SMS to durability-test@upi: You sent ₹4000...
NOTIFICATION SENT! SMS to durability-test@upi: You sent ₹5000...
```

**Check unacked messages again in Console:**

The "Unacked messages" graph should drop from 5 to 0.

### 6.5 Why This Matters

This is **durability** in action:

1. The publisher published 5 messages
2. Pub/Sub stored them durably (replicated across multiple zones)
3. The notification-service was completely DOWN
4. No messages were lost
5. When the service came back, it received ALL 5 messages

**In a real UPI system:** If the notification service goes down at 2 AM,
users' transfer confirmations are not lost. When the service recovers at
2:15 AM, all pending SMS notifications are sent. The user might get the SMS
15 minutes late, but they WILL get it.

---

## Part 7: Cleanup (10 min)

> **Important:** Always clean up GCP resources to avoid unexpected charges.

### 7.1 Delete Cloud Run Services

```bash
echo "Deleting Cloud Run services..."

gcloud run services delete upi-transfer-service \
  --region=asia-south1 --quiet

gcloud run services delete notification-service \
  --region=asia-south1 --quiet

gcloud run services delete fraud-detection-service \
  --region=asia-south1 --quiet

gcloud run services delete analytics-service \
  --region=asia-south1 --quiet

echo "All Cloud Run services deleted."
```

### 7.2 Delete Pub/Sub Resources

```bash
echo "Deleting Pub/Sub subscriptions..."
gcloud pubsub subscriptions delete notification-sub --quiet
gcloud pubsub subscriptions delete fraud-detection-sub --quiet
gcloud pubsub subscriptions delete analytics-sub --quiet
gcloud pubsub subscriptions delete dlq-monitor-sub --quiet

echo "Deleting Pub/Sub topics..."
gcloud pubsub topics delete transaction-events --quiet
gcloud pubsub topics delete transaction-events-dlq --quiet

echo "All Pub/Sub resources deleted."
```

### 7.3 Delete Artifact Registry

```bash
echo "Deleting Artifact Registry repository..."
gcloud artifacts repositories delete upi-services \
  --location=asia-south1 --quiet

echo "Artifact Registry deleted."
```

### 7.4 Delete Monitoring Alert (If Created)

```bash
# List alert policies
gcloud alpha monitoring policies list --format="table(name, displayName)"

# Delete by name (replace with actual policy name from above)
# gcloud alpha monitoring policies delete projects/$PROJECT_ID/alertPolicies/POLICY_ID --quiet
```

### 7.5 Verify Cleanup

```bash
echo "=== Cloud Run Services ==="
gcloud run services list --region=asia-south1

echo ""
echo "=== Pub/Sub Topics ==="
gcloud pubsub topics list

echo ""
echo "=== Pub/Sub Subscriptions ==="
gcloud pubsub subscriptions list

echo ""
echo "=== Artifact Registry ==="
gcloud artifacts repositories list --location=asia-south1
```

All sections should show empty results (or only resources from other projects).

---

## Summary: Local Emulator vs Real GCP

| Factor          | Local Emulator              | Real GCP Pub/Sub                          |
|-----------------|-----------------------------|-------------------------------------------|
| **Throughput**  | Limited by laptop CPU/RAM   | Millions of messages per second            |
| **Latency**     | < 5 ms (localhost)          | 50-200 ms (network + persistence)         |
| **Durability**  | Emulator restart = data lost| Persistent, replicated across zones        |
| **Availability**| Single process, can crash   | 99.95% SLA, multi-zone redundancy         |
| **DLQ**         | Not supported               | Fully supported (max delivery attempts)   |
| **Filtering**   | Not supported               | Server-side attribute filtering           |
| **Ordering**    | Limited support             | Full ordering key support                 |
| **Monitoring**  | Application logs only       | Full metrics, dashboards, alerting        |
| **Cost**        | Free                        | $0.40 per million messages (first 10GB free) |
| **Use case**    | Development and testing     | Staging and production                    |

---

## Key Takeaways

1. **DLQ prevents poison pills:** Failed messages are moved out after N attempts,
   preventing infinite retry loops.

2. **Ordering keys enable sequential processing:** Use sparingly -- only when
   order truly matters (reduces parallelism).

3. **Server-side filtering saves cost:** Let Pub/Sub filter before delivery
   instead of filtering in your code.

4. **Same code, different environment:** Our microservices run on both the
   emulator and real GCP without any code changes. Only environment variables
   change (`PUBSUB_EMULATOR_HOST` present = emulator, absent = real GCP).

5. **Pub/Sub is durable:** Messages survive subscriber downtime. When the
   subscriber recovers, it processes the backlog automatically.

6. **Cloud Run + Pub/Sub = serverless event-driven architecture:** Auto-scaling,
   pay-per-use, no server management.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `docker build` fails with "permission denied" on mvnw | Run `chmod +x mvnw` inside the service directory before building |
| Cloud Run deploy fails with "image not found" | Verify the image exists: `gcloud artifacts docker images list asia-south1-docker.pkg.dev/$PROJECT_ID/upi-services` |
| Subscriber logs show "Permission denied" for Pub/Sub | Ensure the Cloud Run service account has `roles/pubsub.subscriber` role |
| Publisher logs show "Permission denied" for Pub/Sub | Ensure the Cloud Run service account has `roles/pubsub.publisher` role |
| No logs appearing for subscribers | Check `--min-instances=1` is set; without it, Cloud Run may scale to zero |
| Metrics not showing in Console | Metrics can take 1-2 minutes to propagate; refresh the page |
| Transfer API returns 500 on Cloud Run | Check logs: `gcloud run services logs read upi-transfer-service --region=asia-south1 --limit=30` |
| `gcloud auth configure-docker` fails | Run `gcloud auth login` first, then retry |

---

**Congratulations!** You have deployed a production-grade event-driven
microservices architecture on GCP using Pub/Sub and Cloud Run. This is the
same pattern used by real UPI systems handling millions of transactions daily.
