# Lab 01 -- GCP Pub/Sub Level 1: Foundations

## Objective

Understand the fundamentals of Google Cloud Pub/Sub by creating topics, subscriptions, and exchanging messages using a real-time UPI Transfer scenario.

By the end of this lab you will be able to:

- Create and manage Pub/Sub topics and subscriptions using the gcloud CLI
- Publish messages to a topic and pull them from subscriptions
- Demonstrate the fan-out pattern (one message, multiple consumers)
- Understand at-least-once delivery semantics

---

## Prerequisites

- Google Cloud account with an active project
- Cloud Shell or gcloud CLI installed and configured
- Basic understanding of microservices architecture
- Familiarity with JSON message formats

---

## Real-Time Scenario

You are building the **UPI Transfer Service** for a digital payments platform. Currently, the service makes synchronous REST calls to:

- **Notification Service** -- sends SMS and push notifications to users
- **Analytics Service** -- logs transaction data for reporting and dashboards
- **Fraud Detection Service** -- checks transaction patterns for suspicious activity

**The Problem:** If any downstream service is slow or unavailable, the entire transfer fails. A single slow Notification Service call can block the user from completing a payment. This tight coupling creates a fragile system.

**The Solution:** Decouple these services using Google Cloud Pub/Sub. The Transfer Service publishes a single event, and each downstream service independently consumes it at its own pace.

---

## Architecture

### BEFORE -- Synchronous (Tightly Coupled)

```
Transfer Service --REST--> Notification Service
                --REST--> Analytics Service
                --REST--> Fraud Detection Service
```

If Notification Service takes 5 seconds, the user waits 5 extra seconds. If Fraud Detection is down, the entire transfer fails.

### AFTER -- Event-Driven with Pub/Sub (Loosely Coupled)

```
Transfer Service --publish--> [upi-transfer-topic]
                                    |
                      +-------------+-------------+
                      |             |             |
                notification-sub  analytics-sub  fraud-sub
                      |             |             |
                Notification    Analytics     Fraud Detection
                 Service         Service        Service
```

The Transfer Service publishes once and returns immediately. Each downstream service processes the event independently and at its own speed.

---

## Lab Steps

### Step 0: Environment Setup

Open Cloud Shell or your local terminal with gcloud CLI installed.

```bash
# Authenticate (skip if already logged in or using Cloud Shell)
gcloud auth login
```

Set your project ID. Replace `your-project-id` with your actual GCP project ID:

```bash
export PROJECT_ID="your-project-id"
gcloud config set project $PROJECT_ID
```

Enable the Pub/Sub API. This is required before you can create any Pub/Sub resources:

```bash
gcloud services enable pubsub.googleapis.com
```

Verify the API is enabled:

```bash
gcloud services list --enabled --filter="name:pubsub"
```

**Expected output:**

```
NAME                         TITLE
pubsub.googleapis.com        Cloud Pub/Sub API
```

If you do not see the Pub/Sub API listed, re-run the enable command and check for errors.

---

### Step 1: Create a Topic

A **topic** is a named resource to which publishers send messages. Think of it as a broadcast channel -- any message sent to the topic is made available to all attached subscriptions.

In our UPI scenario, `upi-transfer-topic` represents the channel where all completed (or failed) transfer events are announced.

```bash
gcloud pubsub topics create upi-transfer-topic
```

**Expected output:**

```
Created topic [projects/your-project-id/topics/upi-transfer-topic].
```

Verify the topic was created:

```bash
gcloud pubsub topics list
```

**Expected output:**

```
---
name: projects/your-project-id/topics/upi-transfer-topic
```

**Key point:** A topic by itself does nothing. It needs at least one subscription for messages to be delivered anywhere.

---

### Step 2: Create Subscriptions (Multiple Consumers)

A **subscription** is an independent listener attached to a topic. Each subscription receives its own copy of every message published to the topic. This is the **fan-out pattern**.

We will create three subscriptions to simulate three independent downstream services:

```bash
# Notification Service subscription
gcloud pubsub subscriptions create notification-sub \
  --topic=upi-transfer-topic
```

```bash
# Analytics Service subscription
gcloud pubsub subscriptions create analytics-sub \
  --topic=upi-transfer-topic
```

```bash
# Fraud Detection subscription
gcloud pubsub subscriptions create fraud-sub \
  --topic=upi-transfer-topic
```

**Expected output** (for each):

```
Created subscription [projects/your-project-id/subscriptions/notification-sub].
```

Verify all subscriptions were created:

```bash
gcloud pubsub subscriptions list
```

You should see all three subscriptions listed, each pointing to `upi-transfer-topic`.

**Key point:** Each subscription gets its own independent copy of every message. The Notification Service pulling a message does NOT remove it from the Analytics or Fraud Detection subscriptions. They are completely decoupled.

---

### Step 3: Publish a UPI Transfer Event

Now simulate the Transfer Service publishing a completed UPI transaction event to the topic.

```bash
gcloud pubsub topics publish upi-transfer-topic \
  --message='{"transactionId":"TXN001","from":"user-a@upi","to":"user-b@upi","amount":500,"status":"COMPLETED","timestamp":"2026-04-09T10:00:00Z"}'
```

**Expected output:**

```
messageIds:
- '12345678901234567'
```

The `messageId` is a unique identifier assigned by Pub/Sub. Every published message gets one.

**What just happened:** The Transfer Service (simulated by you) published a single message. Pub/Sub internally copied this message to all three subscriptions. Each subscription now has this message waiting to be consumed.

---

### Step 4: Pull Messages from Each Subscription

Now let us verify the **fan-out pattern** -- all three subscriptions should have received the same message.

**Notification Service reads its message:**

```bash
gcloud pubsub subscriptions pull notification-sub --auto-ack
```

**Analytics Service reads its message:**

```bash
gcloud pubsub subscriptions pull analytics-sub --auto-ack
```

**Fraud Detection Service reads its message:**

```bash
gcloud pubsub subscriptions pull fraud-sub --auto-ack
```

**Expected output** (similar for each subscription):

```
DATA                                                                                                                     MESSAGE_ID         ORDERING_KEY  ATTRIBUTES  DELIVERY_ATTEMPT
{"transactionId":"TXN001","from":"user-a@upi","to":"user-b@upi","amount":500,"status":"COMPLETED","timestamp":"..."}     12345678901234567
```

**Key observation:** All three subscriptions received the exact same message. The publisher sent it once, and Pub/Sub distributed it to all consumers. This is the fan-out pattern in action.

The `--auto-ack` flag tells Pub/Sub that the message has been successfully processed and should not be redelivered. We will explore what happens without this flag in Step 9.

---

### Step 5: Publish Multiple Transfer Events

In a real system, transactions happen continuously. Let us publish several more events:

```bash
# Successful transfer of Rs. 1200
gcloud pubsub topics publish upi-transfer-topic \
  --message='{"transactionId":"TXN002","from":"user-c@upi","to":"user-d@upi","amount":1200,"status":"COMPLETED","timestamp":"2026-04-09T10:05:00Z"}'
```

```bash
# Failed transfer of Rs. 75
gcloud pubsub topics publish upi-transfer-topic \
  --message='{"transactionId":"TXN003","from":"user-e@upi","to":"user-f@upi","amount":75,"status":"FAILED","timestamp":"2026-04-09T10:06:00Z"}'
```

```bash
# Successful transfer of Rs. 2500
gcloud pubsub topics publish upi-transfer-topic \
  --message='{"transactionId":"TXN004","from":"user-a@upi","to":"user-g@upi","amount":2500,"status":"COMPLETED","timestamp":"2026-04-09T10:07:00Z"}'
```

Each publish command returns a unique `messageId`.

---

### Step 6: Pull Multiple Messages

Pull all pending messages from a subscription at once using the `--limit` flag:

```bash
gcloud pubsub subscriptions pull notification-sub \
  --limit=10 \
  --auto-ack
```

**Expected output:** You should see all three messages (TXN002, TXN003, TXN004) displayed in a table.

**Note:** The `--limit` flag controls the maximum number of messages to return in a single pull request. In production, your application would pull messages in a continuous loop.

Try pulling from the other subscriptions as well:

```bash
gcloud pubsub subscriptions pull analytics-sub --limit=10 --auto-ack
gcloud pubsub subscriptions pull fraud-sub --limit=10 --auto-ack
```

---

### Step 7: Describe Resources

Use the `describe` command to inspect the details of your Pub/Sub resources.

**Describe the topic:**

```bash
gcloud pubsub topics describe upi-transfer-topic
```

**Expected output:**

```
name: projects/your-project-id/topics/upi-transfer-topic
```

**Describe a subscription:**

```bash
gcloud pubsub subscriptions describe notification-sub
```

**Expected output:**

```
ackDeadlineSeconds: 10
expirationPolicy:
  ttl: 2678400s
messageRetentionDuration: 604800s
name: projects/your-project-id/subscriptions/notification-sub
pushConfig: {}
topic: projects/your-project-id/topics/upi-transfer-topic
```

**Understanding the output fields:**

| Field                      | Meaning                                                                                       |
|----------------------------|-----------------------------------------------------------------------------------------------|
| `ackDeadlineSeconds`       | Time (in seconds) a consumer has to acknowledge a message before it is redelivered. Default: 10 seconds. |
| `expirationPolicy.ttl`     | If no messages are pulled for this duration, the subscription is automatically deleted. Default: 31 days. |
| `messageRetentionDuration` | How long unacknowledged messages are retained. Default: 7 days (604800 seconds).              |
| `pushConfig`               | Empty means this is a **pull** subscription. If configured, Pub/Sub pushes messages to an endpoint. |
| `topic`                    | The topic this subscription is attached to.                                                   |

---

### Step 8: Test Fan-Out Pattern -- Independent Processing

This step demonstrates a critical property: **if one subscription does not pull, messages remain available for it while other subscriptions process independently.**

In a real scenario, this means the Notification Service can be down for maintenance while the Analytics and Fraud Detection services continue processing without any impact.

Publish a new message:

```bash
gcloud pubsub topics publish upi-transfer-topic \
  --message='{"transactionId":"TXN005","from":"user-h@upi","to":"user-i@upi","amount":300,"status":"COMPLETED","timestamp":"2026-04-09T10:10:00Z"}'
```

Pull only from `notification-sub`:

```bash
gcloud pubsub subscriptions pull notification-sub --auto-ack
```

The Notification Service has processed the message. Now check `analytics-sub`:

```bash
gcloud pubsub subscriptions pull analytics-sub --auto-ack
```

**Result:** The message is still available in `analytics-sub` even though `notification-sub` already consumed it. Each subscription maintains its own independent pointer.

This is the core benefit of Pub/Sub in our UPI architecture: if the Fraud Detection Service goes down for 30 minutes, no messages are lost. They accumulate in `fraud-sub` and are processed when the service comes back online.

---

### Step 9: Understanding At-Least-Once Delivery

Pub/Sub guarantees **at-least-once delivery**. This means a message may be delivered more than once if the consumer does not acknowledge it within the ack deadline.

Let us demonstrate this behavior.

**Publish a message:**

```bash
gcloud pubsub topics publish upi-transfer-topic \
  --message='{"transactionId":"TXN006","from":"user-j@upi","to":"user-k@upi","amount":100,"status":"COMPLETED","timestamp":"2026-04-09T10:15:00Z"}'
```

**Pull WITHOUT the `--auto-ack` flag:**

```bash
gcloud pubsub subscriptions pull fraud-sub
```

The message is delivered but NOT acknowledged. Pub/Sub considers it still pending.

**Pull again (wait a few seconds for the ack deadline to pass):**

```bash
gcloud pubsub subscriptions pull fraud-sub
```

**Result:** The same message is redelivered because it was never acknowledged. Pub/Sub will keep redelivering it until it is acknowledged or the retention period expires.

**Now acknowledge it:**

```bash
gcloud pubsub subscriptions pull fraud-sub --auto-ack
```

The message is now acknowledged and will not be delivered again.

**Why this matters for UPI:** Your consumer logic (e.g., the Notification Service) must be **idempotent** -- sending the same SMS notification twice should not cause problems. Common strategies include:

- Tracking processed `transactionId` values in a database
- Using deduplication logic based on `messageId`
- Designing operations to be safe when repeated

---

## Verification Checklist

Before moving to the next lab, confirm you have completed the following:

- [ ] Pub/Sub API enabled in your project
- [ ] Topic `upi-transfer-topic` created successfully
- [ ] Three subscriptions created: `notification-sub`, `analytics-sub`, `fraud-sub`
- [ ] Published a message and verified all three subscriptions received it (fan-out)
- [ ] Published multiple messages and pulled them in a batch
- [ ] Used `describe` to inspect topic and subscription properties
- [ ] Demonstrated independent subscription processing (Step 8)
- [ ] Understood that unacknowledged messages are redelivered (Step 9)
- [ ] Resources described and output fields understood

---

## Cleanup

When you are done experimenting, delete the resources to avoid any charges:

```bash
# Delete subscriptions
gcloud pubsub subscriptions delete notification-sub
gcloud pubsub subscriptions delete analytics-sub
gcloud pubsub subscriptions delete fraud-sub

# Delete topic
gcloud pubsub topics delete upi-transfer-topic
```

**Note:** Deleting a topic does NOT automatically delete its subscriptions. Always delete subscriptions first or separately.

---

## Key Concepts Learned

| #  | Concept                | Summary                                                                                     |
|----|------------------------|---------------------------------------------------------------------------------------------|
| 1  | Topic                  | A named message channel where publishers send events.                                       |
| 2  | Subscription           | An independent listener that receives its own copy of every message from the topic.         |
| 3  | Publisher              | The application that sends messages to a topic (our Transfer Service).                      |
| 4  | Subscriber             | The application that reads messages from a subscription (Notification, Analytics, Fraud).   |
| 5  | Fan-Out Pattern        | One topic with multiple subscriptions; each subscription gets all messages independently.   |
| 6  | At-Least-Once Delivery | Messages may be delivered more than once; consumers must handle duplicates (idempotency).   |
| 7  | Asynchronous Messaging | Publisher and subscriber are decoupled in time; they do not need to be online simultaneously.|
| 8  | Fully Managed          | Pub/Sub is serverless; no brokers to provision, patch, or scale.                            |

---

## Common Mistakes to Avoid

1. **Assuming topics store messages forever.** Default message retention is 7 days. After that, unacknowledged messages are permanently lost.

2. **Not handling duplicate messages in consumer logic.** At-least-once delivery means your consumers MUST be idempotent. Never assume a message arrives exactly once.

3. **Confusing "subscription" with "subscriber."** A subscription is a Pub/Sub resource (created with gcloud). A subscriber is your application code that reads from the subscription. One subscription can have multiple subscriber instances (for load balancing).

4. **Forgetting to enable the Pub/Sub API.** Always run `gcloud services enable pubsub.googleapis.com` before creating resources, otherwise you will get permission errors.

5. **Publishing messages before creating subscriptions.** Messages published before a subscription exists are not retroactively delivered to that subscription. Always create subscriptions first.

6. **Ignoring the ack deadline.** If your consumer takes longer than the ack deadline (default 10 seconds) to process a message, Pub/Sub will redeliver it, causing duplicate processing.

---

## Next Lab

**Level 2: Pull vs Push Subscriptions, Message Acknowledgement, Dead Letter Queues, and Message Ordering** -- We will configure push endpoints, implement fine-grained acknowledgement, handle poison messages with dead letter topics, and explore ordered delivery for UPI transactions.
