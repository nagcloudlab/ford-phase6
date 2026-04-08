# Lab 03: Pub/Sub Deep Dive -- Topics, Subscriptions, Filtering, DLQ, IAM, Snapshots & Emulator

**Duration:** 60 minutes
**Objective:** Master Google Cloud Pub/Sub end-to-end using the UPI Transfer Service context. You will create topics and subscriptions, publish and pull messages, configure server-side filtering, dead-letter queues, message ordering, IAM least-privilege, snapshots with seek, and local emulator testing -- all from the gcloud CLI.

**Scenario:** The `upi-transfer-service` emits transaction events to a central Pub/Sub topic. Multiple downstream consumers -- fraud detection, analytics, audit logging, and push notifications -- each subscribe independently.

---

## Architecture Overview

```
upi-transfer-service
        |
        v
  [upi-txn-events]  (topic)
   /    |     |     \
  v     v     v      v
fraud  analytics  audit  notify
-sub    -sub     -sub    -sub
  |               |
  v               v
[upi-txn-dlq]   (dead-letter topic for fraud-sub)
```

---

## Part 1: Setup -- Enable API and Set Variables

```bash
# Enable the Pub/Sub API (idempotent)
gcloud services enable pubsub.googleapis.com

# Set reusable variables for the entire lab
export PROJECT_ID=$(gcloud config get-value project)
export TOPIC=upi-txn-events
export DLQ_TOPIC=upi-txn-dlq
export FRAUD_SUB=fraud-sub
export ANALYTICS_SUB=analytics-sub
export AUDIT_SUB=audit-sub
export NOTIFY_SUB=notify-sub

echo "Project : $PROJECT_ID"
echo "Topic   : $TOPIC"
echo "DLQ     : $DLQ_TOPIC"
```

Verify the API is enabled:

```bash
gcloud services list --enabled --filter="name:pubsub.googleapis.com" \
  --format="table(name, config.title)"
```

---

## Part 2: Topic CRUD Operations

### 2a. Create Topics

```bash
# Primary event topic
gcloud pubsub topics create $TOPIC

# Dead-letter topic (used later in Part 7)
gcloud pubsub topics create $DLQ_TOPIC
```

### 2b. List All Topics

```bash
gcloud pubsub topics list --format="table(name)"
```

### 2c. Describe a Topic

```bash
gcloud pubsub topics describe $TOPIC
```

### 2d. Update Topic Labels

```bash
gcloud pubsub topics update $TOPIC \
  --update-labels=team=payments,env=lab,service=upi-transfer
```

Verify labels:

```bash
gcloud pubsub topics describe $TOPIC --format="yaml(labels)"
```

### 2e. Delete a Topic (demonstration -- DO NOT run now)

```bash
# DO NOT RUN -- shown for reference only
# gcloud pubsub topics delete $TOPIC --quiet
```

> Deleting a topic does NOT delete its subscriptions, but those subscriptions will stop receiving new messages.

---

## Part 3: Subscriptions -- Pull, Push, Custom Deadlines, Retention

### 3a. Pull Subscription with Custom Ack Deadline

```bash
gcloud pubsub subscriptions create $FRAUD_SUB \
  --topic=$TOPIC \
  --ack-deadline=60 \
  --message-retention-duration=1h \
  --labels=team=fraud,env=lab
```

> `--ack-deadline=60` gives the fraud service 60 seconds to process before redelivery.

### 3b. Pull Subscription with Longer Retention

```bash
gcloud pubsub subscriptions create $ANALYTICS_SUB \
  --topic=$TOPIC \
  --ack-deadline=30 \
  --message-retention-duration=7d \
  --labels=team=analytics,env=lab
```

> Analytics retains messages for 7 days so batch jobs can replay if needed.

### 3c. Pull Subscription for Audit

```bash
gcloud pubsub subscriptions create $AUDIT_SUB \
  --topic=$TOPIC \
  --ack-deadline=30 \
  --message-retention-duration=3d \
  --labels=team=compliance,env=lab
```

### 3d. Push Subscription (for notifications)

```bash
# Push subscriptions send messages via HTTP POST to an endpoint.
# We use a placeholder URL here -- in production this would be a Cloud Run or Cloud Function URL.
gcloud pubsub subscriptions create $NOTIFY_SUB \
  --topic=$TOPIC \
  --push-endpoint="https://example.com/push-handler" \
  --ack-deadline=10 \
  --message-retention-duration=1h \
  --labels=team=notifications,env=lab
```

> Push delivery is ideal for serverless backends. The push endpoint receives a POST with the message payload.

### 3e. List and Verify All Subscriptions

```bash
gcloud pubsub subscriptions list \
  --format="table(name.basename(), topic.basename(), ackDeadlineSeconds, pushConfig.pushEndpoint)"
```

Expected output:

| Subscription | Topic | Ack Deadline | Push Endpoint |
|---|---|---|---|
| fraud-sub | upi-txn-events | 60 | |
| analytics-sub | upi-txn-events | 30 | |
| audit-sub | upi-txn-events | 30 | |
| notify-sub | upi-txn-events | 10 | https://example.com/push-handler |

### 3f. Describe a Subscription in Detail

```bash
gcloud pubsub subscriptions describe $FRAUD_SUB
```

---

## Part 4: Publishing Messages

### 4a. Simple Message

```bash
gcloud pubsub topics publish $TOPIC \
  --message='{"txnId":"UPI-001","from":"nag@upi","to":"ram@upi","amount":500,"status":"SUCCESS"}'
```

### 4b. Message with Attributes

Attributes are key-value metadata attached outside the message body. They power server-side filtering (Part 6).

```bash
gcloud pubsub topics publish $TOPIC \
  --message='{"txnId":"UPI-002","from":"ram@upi","to":"nag@upi","amount":1200,"status":"SUCCESS"}' \
  --attribute="type=TRANSFER,priority=HIGH,region=south"
```

```bash
gcloud pubsub topics publish $TOPIC \
  --message='{"txnId":"UPI-003","from":"merchant@upi","to":"nag@upi","amount":50,"status":"SUCCESS"}' \
  --attribute="type=REFUND,priority=LOW,region=north"
```

### 4c. Publish Multiple Messages in a Loop

```bash
for i in $(seq 4 15); do
  TXN_TYPE=$( [ $((i % 2)) -eq 0 ] && echo "TRANSFER" || echo "PAYMENT" )
  PRIORITY=$( [ $((i % 3)) -eq 0 ] && echo "HIGH" || echo "NORMAL" )
  gcloud pubsub topics publish $TOPIC \
    --message="{\"txnId\":\"UPI-$(printf '%03d' $i)\",\"from\":\"user${i}@upi\",\"to\":\"merchant@upi\",\"amount\":$(( RANDOM % 5000 + 100 )),\"status\":\"SUCCESS\"}" \
    --attribute="type=${TXN_TYPE},priority=${PRIORITY},region=south"
done
echo "Published 12 messages (UPI-004 through UPI-015)"
```

### 4d. Publish with Ordering Key

Ordering keys guarantee FIFO within the same key. This is critical for UPI transaction state machines.

```bash
gcloud pubsub topics publish $TOPIC \
  --message='{"txnId":"UPI-100","status":"INITIATED"}' \
  --ordering-key="nag@upi" \
  --attribute="type=TRANSFER,priority=HIGH"

gcloud pubsub topics publish $TOPIC \
  --message='{"txnId":"UPI-100","status":"PROCESSING"}' \
  --ordering-key="nag@upi" \
  --attribute="type=TRANSFER,priority=HIGH"

gcloud pubsub topics publish $TOPIC \
  --message='{"txnId":"UPI-100","status":"COMPLETED"}' \
  --ordering-key="nag@upi" \
  --attribute="type=TRANSFER,priority=HIGH"

echo "Published 3 ordered messages for txn UPI-100"
```

> Messages with ordering key `nag@upi` are delivered in order: INITIATED -> PROCESSING -> COMPLETED.

---

## Part 5: Pulling and Acknowledging Messages

### 5a. Pull Without Acknowledging

```bash
gcloud pubsub subscriptions pull $FRAUD_SUB \
  --limit=5 \
  --format="table(data, messageId, publishTime, attributes)"
```

> These messages remain unacked. They will be redelivered after the ack deadline (60s for fraud-sub).

### 5b. Pull with Auto-Ack

```bash
gcloud pubsub subscriptions pull $ANALYTICS_SUB \
  --limit=5 \
  --auto-ack \
  --format="table(data, messageId, publishTime, attributes)"
```

> `--auto-ack` immediately acknowledges upon pull. Messages will NOT be redelivered.

### 5c. Pull in JSON Format (for scripting)

```bash
gcloud pubsub subscriptions pull $AUDIT_SUB \
  --limit=3 \
  --auto-ack \
  --format=json
```

> JSON output is ideal for piping into `jq` for automated processing.

### 5d. Manual Ack by ACK_ID

```bash
# Step 1: Pull without ack and capture the ack IDs
gcloud pubsub subscriptions pull $FRAUD_SUB \
  --limit=2 \
  --format="table(data, ackId)"
```

```bash
# Step 2: Copy an ACK_ID from the output above, then acknowledge it manually
# Replace ACK_ID_HERE with the actual value
gcloud pubsub subscriptions ack $FRAUD_SUB \
  --ack-ids="ACK_ID_HERE"
```

```bash
# Step 3: Verify -- pulling again should NOT return the acked message
gcloud pubsub subscriptions pull $FRAUD_SUB \
  --limit=5 \
  --format="table(data, messageId)"
```

> Manual ack gives you "at-least-once" control: process first, ack only on success.

### 5e. Fan-Out Verification

Pull from multiple subscriptions to prove each gets an independent copy:

```bash
echo "=== fraud-sub ==="
gcloud pubsub subscriptions pull $FRAUD_SUB --limit=3 --auto-ack --format="table(data.decode())"
echo ""
echo "=== analytics-sub ==="
gcloud pubsub subscriptions pull $ANALYTICS_SUB --limit=3 --auto-ack --format="table(data.decode())"
echo ""
echo "=== audit-sub ==="
gcloud pubsub subscriptions pull $AUDIT_SUB --limit=3 --auto-ack --format="table(data.decode())"
```

> All three subscriptions receive the same messages independently. This is the Pub/Sub fan-out pattern.

---

## Part 6: Message Filtering (Server-Side)

Server-side filters let Pub/Sub discard messages before delivery, reducing cost and processing load. Filters operate on **attributes only** (not message body).

### 6a. Create a Subscription with a Simple Filter

```bash
# Only receive HIGH priority messages
gcloud pubsub subscriptions create fraud-high-priority \
  --topic=$TOPIC \
  --filter='attributes.priority = "HIGH"' \
  --ack-deadline=30
```

### 6b. Filter with AND Conditions

```bash
# Only receive HIGH priority TRANSFER messages from the south region
gcloud pubsub subscriptions create fraud-high-transfer-south \
  --topic=$TOPIC \
  --filter='attributes.priority = "HIGH" AND attributes.type = "TRANSFER" AND attributes.region = "south"' \
  --ack-deadline=30
```

### 6c. Filter with hasPrefix

```bash
# Match any type that starts with "TRANS" (catches TRANSFER, TRANSACTION, etc.)
gcloud pubsub subscriptions create analytics-trans-prefix \
  --topic=$TOPIC \
  --filter='hasPrefix(attributes.type, "TRANS")' \
  --ack-deadline=30
```

### 6d. Filter with hasAttribute

```bash
# Only receive messages that have the "region" attribute set (regardless of value)
gcloud pubsub subscriptions create audit-with-region \
  --topic=$TOPIC \
  --filter='hasAttribute("region")' \
  --ack-deadline=30
```

### 6e. Test the Filters

Publish test messages and verify filtering works:

```bash
# This message has priority=HIGH and type=TRANSFER
gcloud pubsub topics publish $TOPIC \
  --message='{"txnId":"FILTER-001","amount":9999}' \
  --attribute="type=TRANSFER,priority=HIGH,region=south"

# This message has priority=LOW and type=REFUND
gcloud pubsub topics publish $TOPIC \
  --message='{"txnId":"FILTER-002","amount":50}' \
  --attribute="type=REFUND,priority=LOW,region=north"

# This message has no region attribute
gcloud pubsub topics publish $TOPIC \
  --message='{"txnId":"FILTER-003","amount":200}' \
  --attribute="type=PAYMENT,priority=NORMAL"

sleep 5

echo "=== fraud-high-priority (expect FILTER-001 only) ==="
gcloud pubsub subscriptions pull fraud-high-priority --limit=5 --auto-ack --format="table(data, attributes)"

echo ""
echo "=== fraud-high-transfer-south (expect FILTER-001 only) ==="
gcloud pubsub subscriptions pull fraud-high-transfer-south --limit=5 --auto-ack --format="table(data, attributes)"

echo ""
echo "=== analytics-trans-prefix (expect FILTER-001 only) ==="
gcloud pubsub subscriptions pull analytics-trans-prefix --limit=5 --auto-ack --format="table(data, attributes)"

echo ""
echo "=== audit-with-region (expect FILTER-001 and FILTER-002, NOT FILTER-003) ==="
gcloud pubsub subscriptions pull audit-with-region --limit=5 --auto-ack --format="table(data, attributes)"
```

> **Key rule:** Filtered-out messages are never delivered and do not count toward billing. Filters are immutable -- you cannot change a filter after creation; you must delete and recreate the subscription.

---

## Part 7: Dead Letter Topic (DLQ)

When a message fails processing repeatedly, it should be routed to a dead-letter topic for investigation instead of blocking the subscription.

### 7a. Grant IAM Permissions to Pub/Sub Service Account

The Pub/Sub service agent needs permission to publish to the DLQ topic and acknowledge from the source subscription.

```bash
# Get the project number
export PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format="value(projectNumber)")

# The Pub/Sub service account
export PUBSUB_SA="service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com"

echo "Pub/Sub service account: $PUBSUB_SA"
```

```bash
# Grant publisher role on DLQ topic
gcloud pubsub topics add-iam-policy-binding $DLQ_TOPIC \
  --member="serviceAccount:${PUBSUB_SA}" \
  --role="roles/pubsub.publisher"
```

```bash
# Grant subscriber role on the source subscription (so Pub/Sub can ack dead-lettered messages)
gcloud pubsub subscriptions add-iam-policy-binding $FRAUD_SUB \
  --member="serviceAccount:${PUBSUB_SA}" \
  --role="roles/pubsub.subscriber"
```

### 7b. Update Subscription with Dead-Letter Policy

```bash
gcloud pubsub subscriptions update $FRAUD_SUB \
  --dead-letter-topic=$DLQ_TOPIC \
  --max-delivery-attempts=5
```

> After 5 failed delivery attempts (nacks or ack-deadline expiry), the message is forwarded to `upi-txn-dlq`.

### 7c. Create a DLQ Subscription to Inspect Dead-Lettered Messages

```bash
gcloud pubsub subscriptions create dlq-inspector \
  --topic=$DLQ_TOPIC \
  --ack-deadline=30 \
  --message-retention-duration=7d
```

### 7d. Verify the Dead-Letter Configuration

```bash
gcloud pubsub subscriptions describe $FRAUD_SUB \
  --format="yaml(deadLetterPolicy)"
```

Expected output:

```yaml
deadLetterPolicy:
  deadLetterTopic: projects/YOUR_PROJECT/topics/upi-txn-dlq
  maxDeliveryAttempts: 5
```

### 7e. Simulate Dead-Lettering

```bash
# Publish a "poison" message
gcloud pubsub topics publish $TOPIC \
  --message='{"txnId":"POISON-001","malformed":true}' \
  --attribute="type=TRANSFER,priority=HIGH"

# Pull without ack 5+ times to trigger dead-lettering
for attempt in $(seq 1 6); do
  echo "--- Attempt $attempt: pulling without ack ---"
  gcloud pubsub subscriptions pull $FRAUD_SUB --limit=1 --format="table(data)"
  sleep 2
done

# Check the DLQ after a brief wait
sleep 10
echo "=== Messages in DLQ ==="
gcloud pubsub subscriptions pull dlq-inspector --limit=5 --auto-ack --format="table(data, attributes)"
```

> In production, a Cloud Function or dedicated service monitors the DLQ and raises alerts.

---

## Part 8: Message Ordering

### 8a. Enable Message Ordering on a Subscription

```bash
gcloud pubsub subscriptions create fraud-ordered \
  --topic=$TOPIC \
  --enable-message-ordering \
  --ack-deadline=60
```

### 8b. Publish Ordered Messages

```bash
# Simulate a UPI transaction lifecycle -- order matters!
for STATUS in "INITIATED" "DEBITED" "CREDITED" "COMPLETED"; do
  gcloud pubsub topics publish $TOPIC \
    --message="{\"txnId\":\"UPI-200\",\"status\":\"${STATUS}\"}" \
    --ordering-key="UPI-200" \
    --attribute="type=TRANSFER,priority=HIGH"
  echo "Published: UPI-200 -> $STATUS"
done
```

### 8c. Pull and Verify Order

```bash
gcloud pubsub subscriptions pull fraud-ordered \
  --limit=10 \
  --auto-ack \
  --format="table(data, orderingKey)"
```

> Messages with the same `ordering-key` are delivered in publish order. Different ordering keys have no ordering guarantee relative to each other.

**Important:** If you nack an ordered message, all subsequent messages with that ordering key are paused until the failed message is acked.

---

## Part 9: IAM Least Privilege

In production, workloads should use dedicated service accounts with minimal permissions.

### 9a. Create Service Accounts

```bash
# Service account for publishing (upi-transfer-service)
gcloud iam service-accounts create upi-publisher \
  --display-name="UPI Pub/Sub Publisher"

# Service account for the fraud consumer
gcloud iam service-accounts create fraud-consumer \
  --display-name="Fraud Detection Consumer"

# Service account for the analytics consumer
gcloud iam service-accounts create analytics-consumer \
  --display-name="Analytics Consumer"
```

### 9b. Grant Publisher Role on the Topic Only

```bash
gcloud pubsub topics add-iam-policy-binding $TOPIC \
  --member="serviceAccount:upi-publisher@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/pubsub.publisher"
```

> This account can ONLY publish to `upi-txn-events`. It cannot subscribe, create topics, or access other resources.

### 9c. Grant Subscriber Role Per Subscription

```bash
# Fraud consumer can only pull from fraud-sub
gcloud pubsub subscriptions add-iam-policy-binding $FRAUD_SUB \
  --member="serviceAccount:fraud-consumer@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/pubsub.subscriber"

# Analytics consumer can only pull from analytics-sub
gcloud pubsub subscriptions add-iam-policy-binding $ANALYTICS_SUB \
  --member="serviceAccount:analytics-consumer@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/pubsub.subscriber"
```

### 9d. Verify IAM Bindings

```bash
echo "=== Topic IAM Policy ==="
gcloud pubsub topics get-iam-policy $TOPIC --format=json

echo ""
echo "=== fraud-sub IAM Policy ==="
gcloud pubsub subscriptions get-iam-policy $FRAUD_SUB --format=json

echo ""
echo "=== analytics-sub IAM Policy ==="
gcloud pubsub subscriptions get-iam-policy $ANALYTICS_SUB --format=json
```

> **Principle of least privilege:** Each service account has exactly the permissions it needs and nothing more. A compromised fraud-consumer cannot publish messages or read from analytics-sub.

---

## Part 10: Snapshot and Seek

Snapshots capture the ack state of a subscription at a point in time. Seek lets you replay messages from a snapshot or a timestamp.

### 10a. Create a Snapshot

```bash
# First, ack all pending messages to establish a clean baseline
gcloud pubsub subscriptions pull $ANALYTICS_SUB --limit=100 --auto-ack > /dev/null 2>&1

# Create a snapshot of the current ack state
gcloud pubsub snapshots create analytics-snapshot-01 \
  --subscription=$ANALYTICS_SUB

echo "Snapshot created at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
```

### 10b. Publish New Messages After the Snapshot

```bash
for i in $(seq 1 5); do
  gcloud pubsub topics publish $TOPIC \
    --message="{\"txnId\":\"SNAP-${i}\",\"amount\":$(( RANDOM % 1000 ))}" \
    --attribute="type=TRANSFER,priority=NORMAL"
done
echo "Published 5 post-snapshot messages"
```

### 10c. Pull and Ack the New Messages

```bash
gcloud pubsub subscriptions pull $ANALYTICS_SUB \
  --limit=10 --auto-ack \
  --format="table(data, messageId)"
```

### 10d. Seek Back to Snapshot (Replay Messages)

```bash
gcloud pubsub subscriptions seek $ANALYTICS_SUB \
  --snapshot=analytics-snapshot-01
```

```bash
# Pull again -- you will see the same messages redelivered!
gcloud pubsub subscriptions pull $ANALYTICS_SUB \
  --limit=10 --auto-ack \
  --format="table(data, messageId)"
```

> **Use case:** An analytics pipeline had a bug. After fixing, seek back to the snapshot and reprocess all messages since that point.

### 10e. Seek to a Timestamp

```bash
# Seek to 30 minutes ago (replays messages published after that time)
SEEK_TIME=$(date -u -v-30M +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '30 minutes ago' +%Y-%m-%dT%H:%M:%SZ)
echo "Seeking to: $SEEK_TIME"

gcloud pubsub subscriptions seek $ANALYTICS_SUB \
  --time=$SEEK_TIME
```

```bash
# Pull replayed messages
gcloud pubsub subscriptions pull $ANALYTICS_SUB \
  --limit=10 --auto-ack \
  --format="table(data, messageId, publishTime)"
```

> Timestamp seek does not require a pre-existing snapshot. Useful for ad-hoc replay.

### 10f. List and Delete Snapshots

```bash
# List all snapshots
gcloud pubsub snapshots list --format="table(name, topic, expireTime)"

# Delete when no longer needed
# gcloud pubsub snapshots delete analytics-snapshot-01 --quiet
```

---

## Part 11: Local Emulator

The Pub/Sub emulator lets you develop and test locally without incurring GCP costs or needing network access.

### 11a. Install and Start the Emulator

```bash
# Install the emulator component (one-time setup)
gcloud components install pubsub-emulator 2>/dev/null || echo "Emulator already installed or using apt-based gcloud"

# Start the emulator (runs on port 8085 by default)
gcloud beta emulators pubsub start --project=local-test &
EMULATOR_PID=$!
sleep 5
echo "Emulator started with PID: $EMULATOR_PID"
```

### 11b. Set the Emulator Environment Variable

```bash
# This tells gcloud and client libraries to use the local emulator
export PUBSUB_EMULATOR_HOST=localhost:8085
echo "PUBSUB_EMULATOR_HOST=$PUBSUB_EMULATOR_HOST"
```

### 11c. Test Locally

```bash
# Create a topic on the local emulator
gcloud pubsub topics create test-local-topic

# Create a subscription
gcloud pubsub subscriptions create test-local-sub \
  --topic=test-local-topic \
  --ack-deadline=10

# Publish a test message
gcloud pubsub topics publish test-local-topic \
  --message='{"test":"local emulator works!"}'

# Pull the message
gcloud pubsub subscriptions pull test-local-sub \
  --limit=1 --auto-ack \
  --format="table(data)"
```

### 11d. Stop the Emulator and Unset

```bash
# Stop the emulator
kill $EMULATOR_PID 2>/dev/null
wait $EMULATOR_PID 2>/dev/null

# IMPORTANT: Unset so subsequent commands go to real GCP
unset PUBSUB_EMULATOR_HOST
echo "Emulator stopped. Reconnected to GCP."
```

> **Best practice:** Use the emulator in your CI/CD pipelines for integration tests. Set `PUBSUB_EMULATOR_HOST` in your test configuration and unset it for staging/production.

---

## Part 12: Monitoring and Health Checks

### 12a. Describe Subscription for Health Info

```bash
gcloud pubsub subscriptions describe $FRAUD_SUB \
  --format="yaml(name, topic, ackDeadlineSeconds, messageRetentionDuration, deadLetterPolicy)"
```

### 12b. Check Unacked Message Backlog

```bash
# Use the monitoring metric for oldest unacked message age
gcloud monitoring metrics list --filter='metric.type = "pubsub.googleapis.com/subscription/oldest_unacked_message_age"' \
  --format="table(metric.type, resource.type)" 2>/dev/null || \
  echo "Use Cloud Console: Monitoring > Metrics Explorer > pubsub.googleapis.com/subscription/oldest_unacked_message_age"
```

### 12c. Quick Health Check via Pull

```bash
echo "=== Subscription Health Summary ==="
for SUB in $FRAUD_SUB $ANALYTICS_SUB $AUDIT_SUB; do
  PENDING=$(gcloud pubsub subscriptions pull $SUB --limit=1 --format="value(data)" 2>/dev/null)
  if [ -n "$PENDING" ]; then
    echo "$SUB: has unacked messages (investigate backlog)"
  else
    echo "$SUB: no pending messages (healthy)"
  fi
done
```

### 12d. Create a Monitoring Alert Policy (reference command)

```bash
# Create an alert when oldest unacked message exceeds 5 minutes (300 seconds)
# This uses gcloud alpha -- in production, use Terraform or Console
# gcloud alpha monitoring policies create \
#   --display-name="Pub/Sub Backlog Alert - fraud-sub" \
#   --condition-display-name="Oldest unacked > 5min" \
#   --condition-filter='resource.type="pubsub_subscription" AND resource.label.subscription_id="fraud-sub" AND metric.type="pubsub.googleapis.com/subscription/oldest_unacked_message_age"' \
#   --condition-threshold-value=300 \
#   --condition-threshold-duration=60s \
#   --notification-channels=CHANNEL_ID

echo "In production, set alerts on:"
echo "  - oldest_unacked_message_age > 300s (backlog building up)"
echo "  - num_undelivered_messages > 10000 (subscriber falling behind)"
echo "  - dead_letter_message_count > 0 (poison messages detected)"
```

> **Monitoring trio for Pub/Sub:**
> 1. `oldest_unacked_message_age` -- How stale is the backlog?
> 2. `num_undelivered_messages` -- How deep is the backlog?
> 3. `subscription/dead_letter_message_count` -- Are messages failing permanently?

---

## Part 13: Full Cleanup

```bash
echo "=== Cleaning up all Pub/Sub resources ==="

# Delete filter subscriptions
gcloud pubsub subscriptions delete fraud-high-priority --quiet 2>/dev/null
gcloud pubsub subscriptions delete fraud-high-transfer-south --quiet 2>/dev/null
gcloud pubsub subscriptions delete analytics-trans-prefix --quiet 2>/dev/null
gcloud pubsub subscriptions delete audit-with-region --quiet 2>/dev/null

# Delete ordered subscription
gcloud pubsub subscriptions delete fraud-ordered --quiet 2>/dev/null

# Delete DLQ inspector
gcloud pubsub subscriptions delete dlq-inspector --quiet 2>/dev/null

# Delete main subscriptions
gcloud pubsub subscriptions delete $FRAUD_SUB --quiet 2>/dev/null
gcloud pubsub subscriptions delete $ANALYTICS_SUB --quiet 2>/dev/null
gcloud pubsub subscriptions delete $AUDIT_SUB --quiet 2>/dev/null
gcloud pubsub subscriptions delete $NOTIFY_SUB --quiet 2>/dev/null

# Delete snapshots
gcloud pubsub snapshots delete analytics-snapshot-01 --quiet 2>/dev/null

# Delete topics
gcloud pubsub topics delete $TOPIC --quiet 2>/dev/null
gcloud pubsub topics delete $DLQ_TOPIC --quiet 2>/dev/null

# Delete service accounts
gcloud iam service-accounts delete upi-publisher@${PROJECT_ID}.iam.gserviceaccount.com --quiet 2>/dev/null
gcloud iam service-accounts delete fraud-consumer@${PROJECT_ID}.iam.gserviceaccount.com --quiet 2>/dev/null
gcloud iam service-accounts delete analytics-consumer@${PROJECT_ID}.iam.gserviceaccount.com --quiet 2>/dev/null

# Verify clean state
echo ""
echo "=== Remaining Pub/Sub resources ==="
gcloud pubsub topics list --format="table(name)" 2>/dev/null || echo "No topics"
gcloud pubsub subscriptions list --format="table(name)" 2>/dev/null || echo "No subscriptions"

echo ""
echo "Cleanup complete."
```

---

## Checkpoint

- [ ] Pub/Sub API enabled and environment variables set
- [ ] Created `upi-txn-events` topic with labels
- [ ] Created 4 subscriptions: fraud-sub (pull), analytics-sub (pull), audit-sub (pull), notify-sub (push)
- [ ] Published messages: simple, with attributes, in a loop, with ordering keys
- [ ] Pulled messages: without ack, with auto-ack, in JSON format, manual ack by ACK_ID
- [ ] Verified fan-out: all subscriptions receive independent copies
- [ ] **Message Filtering:** Created subscriptions with attribute filters, AND conditions, hasPrefix, hasAttribute
- [ ] **Dead Letter Queue:** Configured DLQ with IAM grants, max-delivery-attempts, and inspected dead-lettered messages
- [ ] **Message Ordering:** Created ordered subscription, published and verified FIFO delivery
- [ ] **IAM Least Privilege:** Created dedicated service accounts with publisher/subscriber roles scoped to specific resources
- [ ] **Snapshot & Seek:** Created snapshot, replayed messages via seek-to-snapshot and seek-to-timestamp
- [ ] **Local Emulator:** Started emulator, tested locally, stopped and reconnected to GCP
- [ ] **Monitoring:** Inspected subscription health, understood key metrics for alerting
- [ ] Full cleanup completed

---

## Quick Reference Card

| Operation | Command |
|---|---|
| Create topic | `gcloud pubsub topics create TOPIC` |
| Create pull subscription | `gcloud pubsub subscriptions create SUB --topic=TOPIC` |
| Create push subscription | `gcloud pubsub subscriptions create SUB --topic=TOPIC --push-endpoint=URL` |
| Create filtered subscription | `gcloud pubsub subscriptions create SUB --topic=TOPIC --filter='attributes.key = "val"'` |
| Publish message | `gcloud pubsub topics publish TOPIC --message=DATA` |
| Publish with attributes | `gcloud pubsub topics publish TOPIC --message=DATA --attribute="k=v"` |
| Publish with ordering | `gcloud pubsub topics publish TOPIC --message=DATA --ordering-key=KEY` |
| Pull with auto-ack | `gcloud pubsub subscriptions pull SUB --auto-ack --limit=N` |
| Manual ack | `gcloud pubsub subscriptions ack SUB --ack-ids=ID` |
| Configure DLQ | `gcloud pubsub subscriptions update SUB --dead-letter-topic=DLT --max-delivery-attempts=N` |
| Enable ordering | `gcloud pubsub subscriptions create SUB --topic=TOPIC --enable-message-ordering` |
| Create snapshot | `gcloud pubsub snapshots create SNAP --subscription=SUB` |
| Seek to snapshot | `gcloud pubsub subscriptions seek SUB --snapshot=SNAP` |
| Seek to timestamp | `gcloud pubsub subscriptions seek SUB --time=TIMESTAMP` |
| Grant topic IAM | `gcloud pubsub topics add-iam-policy-binding TOPIC --member=SA --role=ROLE` |
| Start emulator | `gcloud beta emulators pubsub start --project=PROJECT` |
| Set emulator env | `export PUBSUB_EMULATOR_HOST=localhost:8085` |
