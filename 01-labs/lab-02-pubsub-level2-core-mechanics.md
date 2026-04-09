# Lab 02 -- GCP Pub/Sub Level 2: Core Mechanics

## Objective

Master Pub/Sub production mechanics: Pull vs Push delivery, message acknowledgement, ack deadlines, retry behavior, Dead Letter Queues, message ordering, and message attributes -- using the UPI Transfer Service scenario.

## Prerequisites

- Completed Lab 01 (Level 1 Foundations)
- Google Cloud project with Pub/Sub API enabled
- Cloud Shell or gcloud CLI

## Real-Time Scenario

The UPI Transfer Service now needs production-grade messaging:
- Notification Service needs real-time push delivery (webhook)
- Analytics Service processes in batches (pull)
- Fraud Detection must handle failures gracefully (DLQ)
- Transaction ordering matters for same-user transfers (ordering keys)

## Part 1: Pull vs Push Subscriptions

### Step 1.1: Create Topic

```bash
gcloud pubsub topics create upi-events
```

### Step 1.2: Create Pull Subscription (Analytics Service)

```bash
gcloud pubsub subscriptions create analytics-pull-sub \
  --topic=upi-events
```

Explain: Analytics service controls when to pull. Good for batch processing. Consumer decides the rate.

### Step 1.3: Create Push Subscription (Notification Service)

For push, you need a public HTTP endpoint. In Cloud Shell you can use a test endpoint:

```bash
# For testing, create a push subscription pointing to a test endpoint
# In production, this would be your Cloud Run service URL
gcloud pubsub subscriptions create notification-push-sub \
  --topic=upi-events \
  --push-endpoint=https://example.com/api/notifications
```

Note: Push will fail to deliver since this is a test endpoint. This demonstrates the concept. In production, use your Cloud Run or App Engine URL.

### Step 1.4: Compare Both

```bash
# Describe pull subscription
gcloud pubsub subscriptions describe analytics-pull-sub

# Describe push subscription
gcloud pubsub subscriptions describe notification-push-sub
```

Note the difference in the pushConfig field.

### Step 1.5: Test Pull Model

```bash
# Publish a message
gcloud pubsub topics publish upi-events \
  --message='{"txnId":"TXN101","type":"TRANSFER","amount":500}'

# Pull it (consumer controls timing)
gcloud pubsub subscriptions pull analytics-pull-sub --auto-ack
```

## Part 2: Message Acknowledgement

### Step 2.1: Create Subscription for ACK Testing

```bash
gcloud pubsub subscriptions create ack-test-sub \
  --topic=upi-events \
  --ack-deadline=10
```

### Step 2.2: Publish a Message

```bash
gcloud pubsub topics publish upi-events \
  --message='{"txnId":"TXN102","amount":1000,"status":"COMPLETED"}'
```

### Step 2.3: Pull WITHOUT Auto-Ack

```bash
# Pull without --auto-ack
gcloud pubsub subscriptions pull ack-test-sub
```

Note the ACK_ID in the output. The message is delivered but NOT acknowledged.

### Step 2.4: Observe Redelivery

```bash
# Wait 10+ seconds (ack deadline), then pull again
# The SAME message is redelivered because it was never acknowledged
gcloud pubsub subscriptions pull ack-test-sub
```

This demonstrates at-least-once delivery. The message keeps coming back until acknowledged.

### Step 2.5: Manually Acknowledge

```bash
# Pull and note the ACK_ID
gcloud pubsub subscriptions pull ack-test-sub

# Acknowledge using the ACK_ID from the output
gcloud pubsub subscriptions acknowledge ack-test-sub \
  --ack-ids="YOUR_ACK_ID_HERE"
```

### Step 2.6: Verify Message is Gone

```bash
# Pull again -- no messages (it was acknowledged)
gcloud pubsub subscriptions pull ack-test-sub
```

### Real-World Insight: ACK Deadline Trap

Explain the scenario:
- Default ack deadline = 10 seconds
- If your UPI fraud check takes 15 seconds to process
- Pub/Sub thinks processing failed at 10 seconds
- Sends the message AGAIN
- Now fraud check runs TWICE on same transaction
- Fix: Increase ack deadline or use modifyAckDeadline

```bash
# Create subscription with longer ack deadline (60 seconds)
gcloud pubsub subscriptions create fraud-sub-long-ack \
  --topic=upi-events \
  --ack-deadline=60
```

## Part 3: Dead Letter Queue (DLQ)

### Step 3.1: Create Dead Letter Topic

```bash
gcloud pubsub topics create upi-dead-letter-topic
```

### Step 3.2: Create DLQ Subscription (to monitor failed messages)

```bash
gcloud pubsub subscriptions create dlq-monitor-sub \
  --topic=upi-dead-letter-topic
```

### Step 3.3: Create Subscription with DLQ Configuration

```bash
# Grant Pub/Sub permission to publish to DLQ topic and manage subscription
PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format="value(projectNumber)")

gcloud pubsub topics add-iam-policy-binding upi-dead-letter-topic \
  --member="serviceAccount:service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com" \
  --role="roles/pubsub.publisher"

gcloud pubsub subscriptions add-iam-policy-binding fraud-detection-sub \
  --member="serviceAccount:service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com" \
  --role="roles/pubsub.subscriber" 2>/dev/null || true

# Create subscription with DLQ
gcloud pubsub subscriptions create fraud-detection-sub \
  --topic=upi-events \
  --dead-letter-topic=upi-dead-letter-topic \
  --max-delivery-attempts=5 \
  --ack-deadline=10
```

### Step 3.4: Simulate Poison Message

```bash
# Publish a "bad" message (imagine invalid JSON that always fails processing)
gcloud pubsub topics publish upi-events \
  --message='INVALID_DATA_CORRUPT_TRANSACTION'
```

### Step 3.5: Simulate Failures (Pull without ACK, 5 times)

```bash
# Each pull without ack counts as a delivery attempt
# After max-delivery-attempts (5), message goes to DLQ

gcloud pubsub subscriptions pull fraud-detection-sub
# Wait 10 seconds for ack deadline to expire
# Repeat pull 4 more times (total 5 attempts)
gcloud pubsub subscriptions pull fraud-detection-sub
gcloud pubsub subscriptions pull fraud-detection-sub
gcloud pubsub subscriptions pull fraud-detection-sub
gcloud pubsub subscriptions pull fraud-detection-sub
```

### Step 3.6: Check Dead Letter Queue

```bash
# After 5 failed attempts, the message should appear in DLQ
gcloud pubsub subscriptions pull dlq-monitor-sub --auto-ack
```

### Real-World Insight

In the UPI scenario, a corrupt transaction message that always fails fraud detection processing would loop forever without DLQ. With DLQ:
- Message fails 5 times
- Moves to dead-letter-topic
- Operations team investigates
- Fix the data and re-publish if needed

## Part 4: Message Ordering

### Step 4.1: Create Ordered Topic

```bash
gcloud pubsub topics create upi-ordered-events \
  --message-ordering
```

### Step 4.2: Create Ordered Subscription

```bash
gcloud pubsub subscriptions create ordered-sub \
  --topic=upi-ordered-events \
  --enable-message-ordering
```

### Step 4.3: Publish Ordered Messages (Same User)

```bash
# Same ordering key (user-a) ensures these are delivered in order
gcloud pubsub topics publish upi-ordered-events \
  --message='{"txnId":"TXN201","user":"user-a","action":"DEBIT","amount":500}' \
  --ordering-key="user-a"

gcloud pubsub topics publish upi-ordered-events \
  --message='{"txnId":"TXN202","user":"user-a","action":"CREDIT","amount":500}' \
  --ordering-key="user-a"

gcloud pubsub topics publish upi-ordered-events \
  --message='{"txnId":"TXN203","user":"user-a","action":"NOTIFY","amount":500}' \
  --ordering-key="user-a"
```

### Step 4.4: Publish Messages for Different Users (Parallel)

```bash
# Different ordering keys = processed in parallel
gcloud pubsub topics publish upi-ordered-events \
  --message='{"txnId":"TXN301","user":"user-b","amount":100}' \
  --ordering-key="user-b"

gcloud pubsub topics publish upi-ordered-events \
  --message='{"txnId":"TXN302","user":"user-c","amount":200}' \
  --ordering-key="user-c"
```

### Step 4.5: Pull and Verify Order

```bash
gcloud pubsub subscriptions pull ordered-sub --limit=10 --auto-ack
```

Messages with same ordering key (user-a) arrive in order: TXN201, TXN202, TXN203.

### Real-World Insight

For UPI: A user's DEBIT must happen before CREDIT, and CREDIT before NOTIFY. Using the user ID as ordering key ensures correct sequence per user, while different users' transactions process in parallel.

## Part 5: Message Attributes

### Step 5.1: Publish with Attributes

```bash
gcloud pubsub topics publish upi-events \
  --message='{"txnId":"TXN401","amount":50000}' \
  --attribute=type=TRANSFER,source=MOBILE,priority=HIGH

gcloud pubsub topics publish upi-events \
  --message='{"txnId":"TXN402","amount":100}' \
  --attribute=type=TRANSFER,source=WEB,priority=LOW
```

### Step 5.2: Pull and See Attributes

```bash
gcloud pubsub subscriptions pull analytics-pull-sub --auto-ack
```

Attributes appear alongside the message data.

### Real-World Insight

Attributes allow subscribers to:
- Filter: Only process HIGH priority transactions
- Route: Mobile transactions to mobile-specific handler
- Classify: Different processing for different event types

## Part 6: Subscription Filtering (Bonus)

### Step 6.1: Create Filtered Subscription

```bash
gcloud pubsub subscriptions create high-priority-sub \
  --topic=upi-events \
  --message-filter='attributes.priority = "HIGH"'
```

### Step 6.2: Test Filter

```bash
# Publish HIGH priority
gcloud pubsub topics publish upi-events \
  --message='{"txnId":"TXN501","amount":75000}' \
  --attribute=priority=HIGH

# Publish LOW priority
gcloud pubsub topics publish upi-events \
  --message='{"txnId":"TXN502","amount":50}' \
  --attribute=priority=LOW

# Only HIGH priority message appears
gcloud pubsub subscriptions pull high-priority-sub --auto-ack
```

## Verification Checklist

- [ ] Created and tested pull subscription
- [ ] Created push subscription (concept understood)
- [ ] Demonstrated message redelivery without ACK
- [ ] Manually acknowledged a message
- [ ] Configured and tested Dead Letter Queue
- [ ] Created ordered topic and verified message ordering
- [ ] Published messages with attributes
- [ ] Tested subscription filtering

## Cleanup

```bash
gcloud pubsub subscriptions delete analytics-pull-sub
gcloud pubsub subscriptions delete notification-push-sub
gcloud pubsub subscriptions delete ack-test-sub
gcloud pubsub subscriptions delete fraud-sub-long-ack
gcloud pubsub subscriptions delete fraud-detection-sub
gcloud pubsub subscriptions delete dlq-monitor-sub
gcloud pubsub subscriptions delete ordered-sub
gcloud pubsub subscriptions delete high-priority-sub

gcloud pubsub topics delete upi-events
gcloud pubsub topics delete upi-dead-letter-topic
gcloud pubsub topics delete upi-ordered-events
```

## Key Concepts Learned

1. Pull = consumer-driven (worker pattern); Push = service-driven (webhook pattern)
2. ACK = success confirmation; No ACK = automatic redelivery
3. Ack deadline defines how long Pub/Sub waits for ACK (default 10s)
4. Dead Letter Queue captures messages that fail repeatedly
5. Ordering keys guarantee sequence for same-key messages
6. Message attributes enable filtering and routing
7. Subscription filters deliver only matching messages

## Common Mistakes to Avoid

1. Not handling duplicate messages (at-least-once means duplicates happen)
2. Setting ack deadline too short for long-running processing
3. Not configuring DLQ (causes infinite retry loops)
4. Using push subscriptions without authentication
5. Assuming message ordering without explicitly enabling it
6. Using ordering when not needed (it reduces throughput)

## Next Lab

Spring Boot Pub/Sub Integration and Production Architecture Patterns
