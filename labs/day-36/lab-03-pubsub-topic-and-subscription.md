# Lab 03: Pub/Sub Topic and Subscription

**Duration:** 30 minutes
**Objective:** Create a Pub/Sub topic and subscription, publish and consume messages using gcloud CLI and the Console.

---

## Prerequisites

- GCP project with billing enabled
- Cloud Shell access

---

## Part 1: Enable the Pub/Sub API

```bash
gcloud services enable pubsub.googleapis.com
```

---

## Part 2: Create a Topic

### Using gcloud CLI

```bash
gcloud pubsub topics create transaction-events
```

### Verify

```bash
gcloud pubsub topics list
```

You should see:

```
name: projects/YOUR_PROJECT_ID/topics/transaction-events
```

### Using the Console

1. Go to **Navigation Menu > Pub/Sub > Topics**
2. You should see `transaction-events` listed
3. Note the **full topic name** format: `projects/{project-id}/topics/{topic-name}`

---

## Part 3: Create Subscriptions

### Create a Pull subscription

```bash
gcloud pubsub subscriptions create transaction-processor \
  --topic=transaction-events \
  --ack-deadline=30 \
  --message-retention-duration=1h
```

### Create a second subscription (to see fan-out)

```bash
gcloud pubsub subscriptions create transaction-logger \
  --topic=transaction-events \
  --ack-deadline=30 \
  --message-retention-duration=1h
```

### Verify

```bash
gcloud pubsub subscriptions list --format="table(name, topic, ackDeadlineSeconds)"
```

| Subscription | Topic | Purpose |
|---|---|---|
| `transaction-processor` | `transaction-events` | Simulates a payment processor |
| `transaction-logger` | `transaction-events` | Simulates an audit logger |

> Both subscriptions receive **independent copies** of every message. This is the fan-out pattern.

---

## Part 4: Publish Messages

### Publish a single message

```bash
gcloud pubsub topics publish transaction-events \
  --message='{"txnId":"UPI-001","from":"nag@upi","to":"ram@upi","amount":500,"status":"SUCCESS"}'
```

### Publish with attributes

```bash
gcloud pubsub topics publish transaction-events \
  --message='{"txnId":"UPI-002","from":"ram@upi","to":"nag@upi","amount":200,"status":"SUCCESS"}' \
  --attribute="type=TRANSFER,priority=HIGH"
```

### Publish multiple messages

```bash
for i in $(seq 3 10); do
  gcloud pubsub topics publish transaction-events \
    --message="{\"txnId\":\"UPI-$(printf '%03d' $i)\",\"from\":\"user${i}@upi\",\"to\":\"merchant@upi\",\"amount\":$(( RANDOM % 1000 + 100 )),\"status\":\"SUCCESS\"}"
done
echo "Published 8 messages"
```

---

## Part 5: Pull and Acknowledge Messages

### Pull from the processor subscription

```bash
gcloud pubsub subscriptions pull transaction-processor \
  --limit=5 \
  --auto-ack \
  --format="table(data, messageId, publishTime, attributes)"
```

You should see your messages with their data, IDs, and timestamps.

### Pull from the logger subscription

```bash
gcloud pubsub subscriptions pull transaction-logger \
  --limit=5 \
  --auto-ack
```

> Both subscriptions received the **same messages** — this proves fan-out is working.

### Pull without auto-ack (manual acknowledgment)

```bash
# Pull without acknowledging
gcloud pubsub subscriptions pull transaction-processor \
  --limit=3 \
  --format="table(data, ackId)"
```

The messages will be **redelivered** after the ack deadline (30 seconds) if not acknowledged.

```bash
# To manually acknowledge, use the ackId from the output:
# gcloud pubsub subscriptions ack transaction-processor --ack-ids="ACK_ID_HERE"
```

---

## Part 6: Explore in the Console

### View Topic Details

1. Go to **Pub/Sub > Topics > transaction-events**
2. Click the **Messages** tab
3. Note: you can publish test messages directly from the Console

### View Subscription Details

1. Go to **Pub/Sub > Subscriptions > transaction-processor**
2. Explore the tabs:

| Tab | What It Shows |
|-----|--------------|
| **Details** | Configuration (ack deadline, retention, etc.) |
| **Messages** | Pull messages directly from the Console |
| **Metrics** | Delivery rate, unacked messages, oldest unacked age |

### Try pulling from the Console

1. Go to **Subscriptions > transaction-processor > Messages**
2. Click **Pull**
3. You should see remaining unacked messages (if any)
4. Click **Ack** to acknowledge them

---

## Part 7: Message Ordering (Optional)

### Create an ordered subscription

```bash
gcloud pubsub topics update transaction-events \
  --message-ordering

gcloud pubsub subscriptions create transaction-ordered \
  --topic=transaction-events \
  --enable-message-ordering \
  --ack-deadline=30
```

### Publish ordered messages

```bash
# Messages with the same ordering key are delivered in order
gcloud pubsub topics publish transaction-events \
  --message='{"txnId":"UPI-100","status":"INITIATED"}' \
  --ordering-key="nag@upi"

gcloud pubsub topics publish transaction-events \
  --message='{"txnId":"UPI-100","status":"PROCESSING"}' \
  --ordering-key="nag@upi"

gcloud pubsub topics publish transaction-events \
  --message='{"txnId":"UPI-100","status":"COMPLETED"}' \
  --ordering-key="nag@upi"
```

> Messages with the same ordering key (`nag@upi`) are guaranteed to arrive in order: INITIATED → PROCESSING → COMPLETED.

---

## Part 8: Cleanup Subscriptions (Keep Topic for Next Lab)

```bash
# Delete the test subscriptions (we'll create Spring Boot ones in Lab 04)
gcloud pubsub subscriptions delete transaction-logger --quiet
gcloud pubsub subscriptions delete transaction-ordered --quiet 2>/dev/null

# Keep transaction-processor and transaction-events for Lab 04
echo "Kept: transaction-events topic + transaction-processor subscription"
```

---

## Checkpoint

- [ ] Pub/Sub API enabled
- [ ] Created `transaction-events` topic
- [ ] Created two subscriptions and observed fan-out behavior
- [ ] Published messages with data and attributes
- [ ] Pulled and acknowledged messages via CLI and Console
- [ ] Understood the difference between auto-ack and manual ack
- [ ] (Optional) Tested message ordering with ordering keys
