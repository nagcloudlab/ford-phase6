# Lab 01: Pub/Sub Microservices with Local Emulator

**Duration:** 90 minutes
**Level:** Intermediate
**Prerequisites:** Java 17+, Maven, Google Cloud SDK installed

---

## Objective

Build and run a **fan-out messaging system** with 4 microservices communicating through
Google Cloud Pub/Sub -- all running locally using the Pub/Sub emulator. No GCP account required.

By the end of this lab you will prove these messaging properties:
- **Fan-out:** one published message reaches multiple independent subscribers
- **Throughput:** measure messages processed per second across services
- **Latency:** measure publish-to-receive time for each service
- **Durability:** messages survive subscriber downtime
- **Availability:** services fail independently without taking down the system

---

## Architecture Overview

```
                         ┌─────────────────────────┐
                         │   Pub/Sub Emulator      │
                         │   localhost:8085         │
                         │                          │
  ┌──────────────┐       │  ┌───────────────────┐   │       ┌──────────────────────┐
  │ upi-transfer │       │  │ topic:            │   │       │ notification-service │
  │   -service   │──────►│  │ transaction-events│──►│──────►│ :8081                │
  │ :8080        │       │  └─────┬─────┬───────┘   │       │ sub: notification-sub│
  │ PUBLISHER    │       │        │     │           │       └──────────────────────┘
  └──────────────┘       │        │     │           │
                         │        │     │           │       ┌──────────────────────┐
                         │        │     └──────────►│──────►│ fraud-detection-svc  │
                         │        │                 │       │ :8082                │
                         │        │                 │       │ sub: fraud-detect-sub│
                         │        │                 │       └──────────────────────┘
                         │        │                 │
                         │        └────────────────►│──────►┌──────────────────────┐
                         │                          │       │ analytics-service    │
                         │                          │       │ :8083                │
                         │                          │       │ sub: analytics-sub   │
                         └──────────────────────────┘       └──────────────────────┘
```

**Key idea:** The publisher sends ONE message. All THREE subscribers receive their own
independent copy. This is the **fan-out pattern**.

---

## Part 1: Start the Pub/Sub Emulator (10 min)

The Pub/Sub emulator is a local, in-memory implementation of the Pub/Sub API. It lets
you develop and test without a GCP account, without incurring costs, and without network
latency to the cloud.

### Step 1.1 -- Verify gcloud SDK is installed

```bash
gcloud --version
```

**Expected output (version may differ):**
```
Google Cloud SDK 467.0.0
beta 2024.01.26
pubsub-emulator 0.8.10
...
```

If the `pubsub-emulator` component is not listed, install it:

```bash
gcloud components install pubsub-emulator
```

### Step 1.2 -- Start the emulator

Open **Terminal 1** (label it "EMULATOR"):

```bash
gcloud beta emulators pubsub start --project=test-project
```

**Expected output:**
```
Executing: /usr/lib/google-cloud-sdk/platform/pubsub-emulator/bin/cloud-pubsub-emulator --host=localhost --port=8085
[pubsub] This is the Google Pub/Sub fake.
[pubsub] Implementation may be incomplete or differ from the real system.
[pubsub] INFO: Server started, listening on 8085
```

Leave this terminal running. The emulator must stay alive for the entire lab.

### Step 1.3 -- Verify the emulator is running

Open a separate terminal and test:

```bash
curl http://localhost:8085
```

You should get an empty response or a small error page -- the point is that it responds,
confirming port 8085 is listening.

### What does the emulator do?

| Feature               | Emulator                     | Real GCP Pub/Sub          |
|-----------------------|------------------------------|---------------------------|
| Cost                  | Free                         | Per-message pricing       |
| Network               | localhost only               | Global                    |
| Persistence           | In-memory (lost on restart)  | Durable (7 days default)  |
| Authentication        | None required                | Service account / IAM     |
| API compatibility     | Same gRPC API                | Same gRPC API             |

The emulator uses the exact same client libraries and API calls as real GCP. Your code
does not change when switching from emulator to production -- only environment variables
change.

---

## Part 2: Start the Publisher -- upi-transfer-service (15 min)

The `upi-transfer-service` is the publisher. When a UPI transfer completes, it publishes
a `TransactionEvent` to the `transaction-events` topic. It also auto-creates all topics
and subscriptions via `PubSubSetup.java`.

### Step 2.1 -- Open a new terminal

Open **Terminal 2** (label it "PUBLISHER").

### Step 2.2 -- Set environment variables and start the service

```bash
cd upi-transfer-service
export PUBSUB_EMULATOR_HOST=localhost:8085
export GCP_PROJECT_ID=test-project
./mvnw spring-boot:run -Dspring-boot.run.profiles=pubsub
```

### Step 2.3 -- Watch the startup logs

Look for PubSubSetup creating topics and subscriptions:

```
========================================
  SETTING UP PUB/SUB RESOURCES
========================================
  Created topic: transaction-events
  Created topic: transaction-events-dlq
  Created subscription: notification-sub → transaction-events
  Created subscription: fraud-detection-sub → transaction-events
  Created subscription: analytics-sub → transaction-events
  Created subscription: dlq-monitor-sub → transaction-events-dlq
========================================
  PUB/SUB SETUP COMPLETE
  Topic: {} → 3 subscriptions (fan-out)
  DLQ:   transaction-events-dlq → 1 subscription
========================================
```

This is the **fan-out setup**: one topic (`transaction-events`) with three subscriptions.
Each subscription is an independent message queue. The DLQ (Dead Letter Queue) topic is
for messages that fail processing -- we will explore that in the next lab.

### Step 2.4 -- Verify the service is healthy

Open **Terminal 3** (label it "CURL" -- use this for all curl commands):

```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
```

**Expected output:**
```json
{
    "status": "UP",
    "components": {
        "db": {
            "status": "UP"
        },
        "diskSpace": {
            "status": "UP"
        },
        "ping": {
            "status": "UP"
        }
    }
}
```

### Step 2.5 -- Test the service without Pub/Sub events

Check a balance:

```bash
curl -s http://localhost:8080/api/balance/nag@upi | python3 -m json.tool
```

**Expected output:**
```json
{
    "upiId": "nag@upi",
    "holderName": "Nagendra",
    "balance": 10000.00
}
```

### Step 2.6 -- Make a transfer and verify publishing

```bash
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "nag@upi", "receiverUpiId": "ram@upi", "amount": 100, "remark": "test"}' \
  | python3 -m json.tool
```

**Expected output:**
```json
{
    "transactionId": "TXN-...",
    "status": "SUCCESS",
    "message": "Transfer successful",
    "senderBalance": 9900.00
}
```

Now check the **PUBLISHER terminal** (Terminal 2). You should see:

```
PUBLISHED | topic=transaction-events | txnId=TXN-... | messageId=1 | from=nag@upi | to=ram@upi | amount=100 | attributes={eventType=TRANSFER, status=SUCCESS, senderUpiId=nag@upi, receiverUpiId=ram@upi, amountRange=LOW}
```

The message is published. But no one is listening yet -- the message sits in the three
subscriptions, waiting for subscribers.

---

## Part 3: Start Subscriber #1 -- notification-service (10 min)

The notification service simulates sending SMS notifications to the sender and receiver
of each transaction.

### Step 3.1 -- Open a new terminal

Open **Terminal 4** (label it "NOTIFICATION").

### Step 3.2 -- Set environment variables and start the service

```bash
cd notification-service
export PUBSUB_EMULATOR_HOST=localhost:8085
export GCP_PROJECT_ID=test-project
./mvnw spring-boot:run
```

### Step 3.3 -- Watch the startup logs

```
====================================================
  NOTIFICATION SERVICE — subscribing to: notification-sub
====================================================
```

If you made the transfer in Part 2, the notification service will immediately process
that message from the backlog:

```
-----------------------------------------------
  NOTIFICATION SENT!
  SMS to nag@upi: You sent ₹100 to ram@upi
  SMS to ram@upi: You received ₹100 from nag@upi
  TxnId: TXN-... | MessageId: 1 | Latency: ...ms
  Total messages processed: 1 | Avg latency: ...ms
-----------------------------------------------
```

### Step 3.4 -- Make a new transfer and watch real-time processing

In the **CURL terminal** (Terminal 3):

```bash
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "priya@upi", "receiverUpiId": "nag@upi", "amount": 250, "remark": "lunch"}' \
  | python3 -m json.tool
```

Watch the **NOTIFICATION terminal** -- within milliseconds you should see:

```
  NOTIFICATION SENT!
  SMS to priya@upi: You sent ₹250 to nag@upi
  SMS to nag@upi: You received ₹250 from priya@upi
```

### Step 3.5 -- Check notification service stats

```bash
curl -s http://localhost:8081/stats | python3 -m json.tool
```

**Expected output:**
```json
{
    "service": "notification-service",
    "messagesProcessed": 2,
    "avgLatencyMs": 15,
    "uptimeSeconds": 45,
    "throughputPerSecond": 0.04
}
```

### Understanding the message flow

```
curl POST /api/transfer
  → TransferService processes the transfer
    → TransactionEventPublisher publishes to "transaction-events" topic
      → Pub/Sub delivers to "notification-sub" subscription
        → NotificationSubscriber receives and processes the message
          → Logs "NOTIFICATION SENT!"
          → Calls message.ack() to acknowledge
```

The `ack()` call is critical: it tells Pub/Sub "I processed this message successfully,
you can delete it from my subscription." Without `ack()`, Pub/Sub will redeliver the
message.

---

## Part 4: Start Subscriber #2 -- fraud-detection-service (10 min)

The fraud detection service applies two rules:
1. **High value:** transactions >= 5000 are flagged
2. **Rapid transfers:** more than 3 transfers from the same sender within 60 seconds

### Step 4.1 -- Open a new terminal and start the service

Open **Terminal 5** (label it "FRAUD"):

```bash
cd fraud-detection-service
export PUBSUB_EMULATOR_HOST=localhost:8085
export GCP_PROJECT_ID=test-project
./mvnw spring-boot:run
```

**Expected startup log:**
```
====================================================
  FRAUD DETECTION SERVICE — subscribing to: fraud-detection-sub
  High amount threshold: ₹5000
  Rapid transfer: >3 in 60s
====================================================
```

It will also immediately process the backlog of 2 messages from earlier.

### Step 4.2 -- Test: Small transfer (CLEAN)

```bash
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "nag@upi", "receiverUpiId": "priya@upi", "amount": 500, "remark": "small"}' \
  | python3 -m json.tool
```

Watch the **FRAUD terminal**:

```
  FRAUD CHECK | TxnId: TXN-... | nag@upi → priya@upi | ₹500
  Attributes: {eventType=TRANSFER, status=SUCCESS, senderUpiId=nag@upi, receiverUpiId=priya@upi, amountRange=LOW}
  Result: CLEAN — no fraud indicators
```

### Step 4.3 -- Test: Large transfer (HIGH VALUE ALERT)

```bash
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "nag@upi", "receiverUpiId": "ram@upi", "amount": 6000, "remark": "big purchase"}' \
  | python3 -m json.tool
```

Watch the **FRAUD terminal**:

```
  FRAUD CHECK | TxnId: TXN-... | nag@upi → ram@upi | ₹6000
  Attributes: {eventType=TRANSFER, status=SUCCESS, senderUpiId=nag@upi, receiverUpiId=ram@upi, amountRange=HIGH}
  ALERT: HIGH VALUE TRANSACTION! ₹6000 exceeds threshold ₹5000
  Action: Flagged for manual review
```

### Step 4.4 -- Test: Rapid transfers (RAPID TRANSFER ALERT)

Send 4 transfers quickly from the same sender. Run these commands one after another:

```bash
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "priya@upi", "receiverUpiId": "ram@upi", "amount": 100, "remark": "rapid1"}'

curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "priya@upi", "receiverUpiId": "nag@upi", "amount": 200, "remark": "rapid2"}'

curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "priya@upi", "receiverUpiId": "test@upi", "amount": 150, "remark": "rapid3"}'

curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "priya@upi", "receiverUpiId": "ford@upi", "amount": 100, "remark": "rapid4"}'
```

On the 4th transfer, the **FRAUD terminal** shows:

```
  ALERT: RAPID TRANSFERS! priya@upi made 4 transfers in 60s window
  Action: Account temporarily flagged
```

### Step 4.5 -- Check fraud detection stats

```bash
curl -s http://localhost:8082/stats | python3 -m json.tool
```

**Expected output:**
```json
{
    "service": "fraud-detection-service",
    "messagesProcessed": 8,
    "fraudAlertsRaised": 2,
    "avgLatencyMs": 12,
    "uptimeSeconds": 120,
    "throughputPerSecond": 0.07
}
```

---

## Part 5: Start Subscriber #3 -- analytics-service (10 min)

The analytics service tracks real-time transaction metrics: volume, amounts, top senders,
and amount distribution buckets.

### Step 5.1 -- Open a new terminal and start the service

Open **Terminal 6** (label it "ANALYTICS"):

```bash
cd analytics-service
export PUBSUB_EMULATOR_HOST=localhost:8085
export GCP_PROJECT_ID=test-project
./mvnw spring-boot:run
```

**Expected startup log:**
```
====================================================
  ANALYTICS SERVICE — subscribing to: analytics-sub
  Tracking: volume, amounts, top users, latency
====================================================
```

It will process the entire backlog of all previous messages.

### Step 5.2 -- Make a few more transfers

```bash
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "ram@upi", "receiverUpiId": "priya@upi", "amount": 3000, "remark": "rent"}'

curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "test@upi", "receiverUpiId": "nag@upi", "amount": 750, "remark": "dinner"}'
```

### Step 5.3 -- Check analytics stats

```bash
curl -s http://localhost:8083/stats | python3 -m json.tool
```

**Expected output (values will vary):**
```json
{
    "service": "analytics-service",
    "messagesProcessed": 10,
    "avgLatencyMs": 18,
    "uptimeSeconds": 60,
    "throughputPerSecond": 0.17,
    "totalTransactionVolume": 11450.00,
    "maxTransaction": 6000,
    "minTransaction": 100,
    "amountDistribution": {
        "LOW (<1000)": 7,
        "MEDIUM (1000-5000)": 1,
        "HIGH (>5000)": 2
    },
    "topSenders": {
        "nag@upi": 4,
        "priya@upi": 4,
        "ram@upi": 1,
        "test@upi": 1
    },
    "topReceivers": {
        "ram@upi": 3,
        "nag@upi": 3,
        "priya@upi": 2,
        "test@upi": 1,
        "ford@upi": 1
    }
}
```

### Step 5.4 -- Verify fan-out

All THREE subscribers processed the SAME messages independently. Compare the stats:

```bash
echo "=== NOTIFICATION ===" && curl -s http://localhost:8081/stats | python3 -m json.tool
echo "=== FRAUD ===" && curl -s http://localhost:8082/stats | python3 -m json.tool
echo "=== ANALYTICS ===" && curl -s http://localhost:8083/stats | python3 -m json.tool
```

All three should show the same `messagesProcessed` count. Each subscription gets its own
copy of every message. This is the **fan-out pattern** -- publish once, process many ways.

---

## Part 6: Demonstrate the Four Factors (20 min)

Now that all services are running, let us measure and prove four critical messaging
properties.

### 6.1 -- Throughput

**Goal:** Measure how fast each service processes messages under load.

Send 20 transfers rapidly using a bash loop:

```bash
for i in $(seq 1 20); do
  curl -s -X POST http://localhost:8080/api/transfer \
    -H "Content-Type: application/json" \
    -d "{\"senderUpiId\": \"nag@upi\", \"receiverUpiId\": \"ram@upi\", \"amount\": $((RANDOM % 9000 + 100)), \"remark\": \"load-test-$i\"}" &
done
wait
echo "All 20 transfers sent!"
```

Wait a few seconds, then check throughput on all services:

```bash
echo "=== NOTIFICATION ===" && curl -s http://localhost:8081/stats | python3 -m json.tool
echo "=== FRAUD ===" && curl -s http://localhost:8082/stats | python3 -m json.tool
echo "=== ANALYTICS ===" && curl -s http://localhost:8083/stats | python3 -m json.tool
```

**What to observe:**
- All three services should show the same `messagesProcessed` count (30 = 10 earlier + 20 new)
- `throughputPerSecond` shows each service's processing rate
- Each service processes messages independently and at its own pace

### 6.2 -- Latency

**Goal:** Compare processing latency across services.

Look at `avgLatencyMs` from the stats output above:

| Service              | Expected Latency | Why?                                   |
|----------------------|-------------------|----------------------------------------|
| notification-service | Lowest (~5-15ms)  | Simplest processing -- just logs       |
| fraud-detection-svc  | Medium (~10-20ms) | Two fraud rules to evaluate            |
| analytics-service    | Highest (~15-30ms)| Most computation (buckets, min/max, etc)|

The latency measures the time from when the message was published to when the subscriber
received it. On the local emulator, latency is very low. On real GCP with services in
different regions, you would see higher numbers.

### 6.3 -- Durability

**Goal:** Prove that messages survive subscriber downtime.

**Step 1:** Stop the notification service. Go to the **NOTIFICATION terminal** and press `Ctrl+C`.

```
# Terminal 4 (NOTIFICATION): Press Ctrl+C
```

**Step 2:** Make 5 more transfers while notification-service is down:

```bash
for i in $(seq 1 5); do
  curl -s -X POST http://localhost:8080/api/transfer \
    -H "Content-Type: application/json" \
    -d "{\"senderUpiId\": \"test@upi\", \"receiverUpiId\": \"ford@upi\", \"amount\": $((i * 100)), \"remark\": \"durability-test-$i\"}"
  echo ""
done
```

**Step 3:** Verify the publisher still works (check PUBLISHER terminal for 5 PUBLISHED logs).

**Step 4:** Verify fraud and analytics received all 5:

```bash
echo "=== FRAUD ===" && curl -s http://localhost:8082/stats | python3 -m json.tool
echo "=== ANALYTICS ===" && curl -s http://localhost:8083/stats | python3 -m json.tool
```

Both should show 5 more messages than before. But notification is down.

**Step 5:** Restart notification-service:

```bash
cd notification-service
export PUBSUB_EMULATOR_HOST=localhost:8085
export GCP_PROJECT_ID=test-project
./mvnw spring-boot:run
```

**Step 6:** Watch the NOTIFICATION terminal -- it immediately processes the backlog of 5 messages!

```
  NOTIFICATION SENT!
  SMS to test@upi: You sent ₹100 to ford@upi
  ...
  NOTIFICATION SENT!
  SMS to test@upi: You sent ₹500 to ford@upi
```

**Step 7:** Verify all services are caught up:

```bash
echo "=== NOTIFICATION ===" && curl -s http://localhost:8081/stats | python3 -m json.tool
```

The `messagesProcessed` count should now be 5 (the 5 it missed while down).

> **This is durability.** Messages are stored in the subscription until the subscriber
> acknowledges them. Even if the subscriber crashes, restarts, or is down for hours,
> no messages are lost. On real GCP, messages persist for up to 7 days by default.

### 6.4 -- Availability

**Goal:** Prove that services fail independently.

**Step 1:** Stop the fraud-detection-service. Go to the **FRAUD terminal** and press `Ctrl+C`.

```
# Terminal 5 (FRAUD): Press Ctrl+C
```

**Step 2:** Make a transfer:

```bash
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "nag@upi", "receiverUpiId": "priya@upi", "amount": 300, "remark": "availability test"}' \
  | python3 -m json.tool
```

**Step 3:** Check the other services:

```bash
curl -s http://localhost:8081/stats | python3 -m json.tool
curl -s http://localhost:8083/stats | python3 -m json.tool
```

Notification and analytics both received the message. Fraud is down, but the rest of the
system is unaffected.

**Step 4:** Try to reach the downed service:

```bash
curl -s http://localhost:8082/stats
```

No response -- the service is down. But the system **degrades gracefully**, not
catastrophically. Two out of three subscribers still work.

> **This is availability.** In a monolith, if the fraud module crashes, the entire
> application goes down. With Pub/Sub microservices, each service is independent.
> The system continues to function with reduced capability.

---

## Part 7: Explore Message Attributes (15 min)

Message attributes are key-value metadata attached to each published message. They travel
alongside the message payload but are accessible without deserializing the JSON body.

### Step 7.1 -- Observe attributes in fraud-detection logs

Restart the fraud-detection-service if it is still stopped:

```bash
cd fraud-detection-service
export PUBSUB_EMULATOR_HOST=localhost:8085
export GCP_PROJECT_ID=test-project
./mvnw spring-boot:run
```

Make a transfer:

```bash
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId": "nag@upi", "receiverUpiId": "ram@upi", "amount": 7500, "remark": "attributes demo"}' \
  | python3 -m json.tool
```

Watch the **FRAUD terminal**:

```
  FRAUD CHECK | TxnId: TXN-... | nag@upi → ram@upi | ₹7500
  Attributes: {eventType=TRANSFER, status=SUCCESS, senderUpiId=nag@upi, receiverUpiId=ram@upi, amountRange=HIGH}
  ALERT: HIGH VALUE TRANSACTION! ₹7500 exceeds threshold ₹5000
```

### Step 7.2 -- Understand the publisher code that sets attributes

The publisher (`TransactionEventPublisher.java`) attaches five attributes to every message:

```java
Map<String, String> attributes = Map.of(
    "eventType", "TRANSFER",
    "status", event.getStatus(),
    "senderUpiId", event.getSenderUpiId(),
    "receiverUpiId", event.getReceiverUpiId(),
    "amountRange", categorizeAmount(event.getAmount().doubleValue())
);

pubSubTemplate.publish(topicName, json, attributes);
```

The `amountRange` attribute is computed by the publisher:
- `LOW` -- under 1,000
- `MEDIUM` -- 1,000 to 9,999
- `HIGH` -- 10,000 and above

### Step 7.3 -- Understand the subscriber code that reads attributes

The fraud detection subscriber reads attributes from the incoming message:

```java
Map<String, String> attributes = message.getPubsubMessage().getAttributesMap();
log.info("  Attributes: {}", attributes);
```

Attributes are available as a simple `Map<String, String>` without needing to parse
the JSON payload.

### Step 7.4 -- Why attributes matter on real GCP

On the local emulator, all three subscriptions receive every message. On real GCP,
you can create **filtered subscriptions** that only deliver messages matching an
attribute filter:

```
# Example: Create a subscription that only receives HIGH-value transactions
gcloud pubsub subscriptions create high-value-fraud-sub \
  --topic=transaction-events \
  --filter='attributes.amountRange = "HIGH"'
```

This means the fraud service would only receive messages worth investigating,
reducing processing costs and improving efficiency.

| Use Case                    | Attribute Filter                          |
|-----------------------------|-------------------------------------------|
| High-value fraud alerts     | `attributes.amountRange = "HIGH"`         |
| Failed transaction monitor  | `attributes.status = "FAILED"`            |
| Specific sender monitoring  | `attributes.senderUpiId = "suspect@upi"`  |

We will implement filtered subscriptions in the next lab when we deploy to real GCP.

---

## Wrap-up

### What we built

```
Terminal 1: Pub/Sub Emulator (localhost:8085)
Terminal 2: upi-transfer-service (PUBLISHER, :8080)
Terminal 3: curl commands
Terminal 4: notification-service (SUBSCRIBER, :8081)
Terminal 5: fraud-detection-service (SUBSCRIBER, :8082)
Terminal 6: analytics-service (SUBSCRIBER, :8083)
```

### What we proved

| Property     | How we proved it                                              |
|--------------|---------------------------------------------------------------|
| Fan-out      | One published message reached all 3 subscribers independently |
| Throughput   | Sent 20 messages in a burst, measured processing rate         |
| Latency      | Compared avgLatencyMs across services                         |
| Durability   | Stopped notification-service, sent messages, restarted -- no loss |
| Availability | Killed fraud-detection, other services kept working           |

### Key concepts

- **Topic:** A named channel where publishers send messages (`transaction-events`)
- **Subscription:** An independent message queue attached to a topic (`notification-sub`, `fraud-detection-sub`, `analytics-sub`)
- **Publisher:** Sends messages to a topic (`upi-transfer-service`)
- **Subscriber:** Pulls messages from a subscription (the 3 subscriber services)
- **Ack:** Tells Pub/Sub "I processed this, delete it from my subscription"
- **Nack:** Tells Pub/Sub "I failed, redeliver this message later"
- **Attributes:** Key-value metadata on messages, useful for filtering and routing
- **Fan-out:** One message → multiple independent subscribers via multiple subscriptions
- **DLQ:** Dead Letter Queue for messages that repeatedly fail processing

### Cleanup

Stop all services with `Ctrl+C` in each terminal. The emulator's data is in-memory
and will be lost when you stop it -- no cleanup needed.

### Tomorrow: Day 38

- Dead Letter Queues (DLQ) -- what happens when a message fails repeatedly?
- Message ordering -- guaranteeing FIFO within an ordering key
- Filtered subscriptions -- using attributes to route messages
- Deploy all services to **GCP Cloud Run** with real Pub/Sub
