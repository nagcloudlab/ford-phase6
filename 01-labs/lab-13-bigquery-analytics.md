# Lab 13: BigQuery — Streaming UPI Transaction Analytics

## Objectives
- Enable BigQuery API and understand datasets/tables
- Deploy analytics-service-bigquery to Cloud Run
- Stream transaction events from Pub/Sub into BigQuery
- Run SQL queries to analyze transaction data
- Explore the BigQuery Console

---

## Prerequisites
- GCP project with billing enabled
- `gcloud` CLI authenticated
- Pub/Sub topic `transaction-events` and subscription `analytics-sub` already exist (from Pub/Sub lab)
- UPI Transfer Service running (with `--spring.profiles.active=pubsub`)

---

## Part 1: Enable APIs

```bash
export PROJECT_ID=$(gcloud config get-value project)
export REGION=us-central1

gcloud services enable bigquery.googleapis.com
gcloud services enable cloudbuild.googleapis.com
gcloud services enable run.googleapis.com
```

---

## Part 2: Explore BigQuery from CLI

Before deploying the service, let's understand BigQuery basics.

### Create a dataset manually

```bash
bq mk --dataset --location=US $PROJECT_ID:upi_analytics
```

### Create a table manually

```bash
bq mk --table \
  $PROJECT_ID:upi_analytics.transactions \
  transaction_id:INT64,sender_upi_id:STRING,receiver_upi_id:STRING,amount:NUMERIC,status:STRING,timestamp:TIMESTAMP,ingested_at:TIMESTAMP
```

### Verify

```bash
bq show $PROJECT_ID:upi_analytics.transactions
```

### Insert a test row

```bash
echo '{"transaction_id":1,"sender_upi_id":"nag@upi","receiver_upi_id":"ram@upi","amount":500.00,"status":"SUCCESS","timestamp":"2026-04-09T10:00:00","ingested_at":"2026-04-09T10:00:01"}' | \
  bq insert upi_analytics.transactions
```

### Query it

```bash
bq query --use_legacy_sql=false \
  'SELECT * FROM upi_analytics.transactions'
```

> **Note:** The service auto-creates the dataset and table on startup. We're creating them manually here to learn the CLI.

---

## Part 3: Grant IAM Permissions

Cloud Run's default service account needs BigQuery access.

```bash
# Get the default compute service account
export SA=$(gcloud iam service-accounts list \
  --filter="displayName:Compute Engine default" \
  --format="value(email)")

# Grant BigQuery Data Editor role
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:$SA" \
  --role="roles/bigquery.dataEditor"

# Grant BigQuery Job User role (needed to create tables)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:$SA" \
  --role="roles/bigquery.jobUser"
```

---

## Part 4: Build & Deploy analytics-service-bigquery

### Build the container

```bash
cd analytics-service-bigquery

gcloud builds submit --tag gcr.io/$PROJECT_ID/analytics-bq
```

### Deploy to Cloud Run

```bash
gcloud run deploy analytics-bq \
  --image=gcr.io/$PROJECT_ID/analytics-bq \
  --region=$REGION \
  --platform=managed \
  --allow-unauthenticated \
  --set-env-vars="GCP_PROJECT_ID=$PROJECT_ID,BQ_DATASET=upi_analytics,BQ_TABLE=transactions,PUBSUB_SUBSCRIPTION=analytics-sub" \
  --no-cpu-throttling \
  --min-instances=1 \
  --memory=512Mi \
  --port=8083
```

**Important flags:**
- `--no-cpu-throttling`: Keeps CPU active for Pub/Sub subscriber (always listening)
- `--min-instances=1`: Always keep one instance warm

### Get the service URL

```bash
export ANALYTICS_URL=$(gcloud run services describe analytics-bq \
  --region=$REGION \
  --format="value(status.url)")

echo "Analytics Service: $ANALYTICS_URL"
```

### Check it's running

```bash
curl -s $ANALYTICS_URL/stats | jq
```

You should see BigQuery stats in the response:
```json
{
  "messagesProcessed": 0,
  "bigQuery": {
    "dataset": "upi_analytics",
    "table": "transactions",
    "rowsInserted": 0,
    "insertErrors": 0
  }
}
```

---

## Part 5: Generate Transaction Data

Make sure your UPI Transfer Service is running with Pub/Sub enabled. Then fire some transfers:

```bash
# Get UPI service URL (if on Cloud Run)
export UPI_URL=$(gcloud run services describe upi-transfer-service \
  --region=$REGION \
  --format="value(status.url)" 2>/dev/null || echo "http://localhost:8080")

# Send 5 transfers
for i in $(seq 1 5); do
  curl -s -X POST $UPI_URL/api/transfer \
    -H "Content-Type: application/json" \
    -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":'$((i * 1000))',"remark":"bq-test-'$i'"}' | jq '.status'
done
```

### Check analytics stats

```bash
curl -s $ANALYTICS_URL/stats | jq
```

You should see:
- `messagesProcessed: 5`
- `bigQuery.rowsInserted: 5`

---

## Part 6: Query Your Data in BigQuery

### From CLI

```bash
# All transactions
bq query --use_legacy_sql=false \
  'SELECT transaction_id, sender_upi_id, receiver_upi_id, amount, status, timestamp
   FROM upi_analytics.transactions
   ORDER BY timestamp DESC'

# Total volume
bq query --use_legacy_sql=false \
  'SELECT
     COUNT(*) AS total_transactions,
     SUM(amount) AS total_volume,
     AVG(amount) AS avg_amount,
     MAX(amount) AS max_amount
   FROM upi_analytics.transactions
   WHERE status = "SUCCESS"'

# Top senders
bq query --use_legacy_sql=false \
  'SELECT sender_upi_id,
          COUNT(*) AS txn_count,
          SUM(amount) AS total_sent
   FROM upi_analytics.transactions
   WHERE status = "SUCCESS"
   GROUP BY sender_upi_id
   ORDER BY total_sent DESC'

# Pipeline latency
bq query --use_legacy_sql=false \
  'SELECT
     AVG(TIMESTAMP_DIFF(ingested_at, timestamp, SECOND)) AS avg_latency_sec,
     MAX(TIMESTAMP_DIFF(ingested_at, timestamp, SECOND)) AS max_latency_sec
   FROM upi_analytics.transactions'
```

### From BigQuery Console

1. Go to **console.cloud.google.com/bigquery**
2. In the left panel, expand your project &rarr; `upi_analytics` &rarr; `transactions`
3. Click **Preview** to see raw data
4. Click **Compose new query** and try:

```sql
-- Daily transaction summary
SELECT
  DATE(timestamp) AS day,
  COUNT(*) AS txn_count,
  SUM(amount) AS volume,
  COUNT(CASE WHEN status = 'FAILED' THEN 1 END) AS failures
FROM upi_analytics.transactions
GROUP BY day
ORDER BY day DESC
```

---

## Part 7: Advanced Queries

### Amount distribution

```bash
bq query --use_legacy_sql=false \
  'SELECT
     CASE
       WHEN amount < 1000 THEN "LOW (<1000)"
       WHEN amount < 5000 THEN "MEDIUM (1000-5000)"
       ELSE "HIGH (>5000)"
     END AS bucket,
     COUNT(*) AS count,
     SUM(amount) AS total
   FROM upi_analytics.transactions
   GROUP BY bucket
   ORDER BY total DESC'
```

### Hourly activity pattern

```bash
bq query --use_legacy_sql=false \
  'SELECT
     EXTRACT(HOUR FROM timestamp) AS hour,
     COUNT(*) AS txn_count
   FROM upi_analytics.transactions
   GROUP BY hour
   ORDER BY hour'
```

### Sender-receiver pairs

```bash
bq query --use_legacy_sql=false \
  'SELECT
     sender_upi_id,
     receiver_upi_id,
     COUNT(*) AS transfers,
     SUM(amount) AS total_amount
   FROM upi_analytics.transactions
   WHERE status = "SUCCESS"
   GROUP BY 1, 2
   ORDER BY total_amount DESC
   LIMIT 10'
```

---

## Part 8: Load More Test Data (Batch Insert)

To have more data to query, let's batch-insert some historical rows:

```bash
cat > /tmp/bq_test_data.json << 'EOF'
{"transaction_id":101,"sender_upi_id":"nag@upi","receiver_upi_id":"priya@upi","amount":2500.00,"status":"SUCCESS","timestamp":"2026-04-08T09:30:00","ingested_at":"2026-04-08T09:30:01"}
{"transaction_id":102,"sender_upi_id":"ram@upi","receiver_upi_id":"nag@upi","amount":7500.00,"status":"SUCCESS","timestamp":"2026-04-08T11:45:00","ingested_at":"2026-04-08T11:45:01"}
{"transaction_id":103,"sender_upi_id":"priya@upi","receiver_upi_id":"ford@upi","amount":150.00,"status":"SUCCESS","timestamp":"2026-04-08T14:20:00","ingested_at":"2026-04-08T14:20:01"}
{"transaction_id":104,"sender_upi_id":"nag@upi","receiver_upi_id":"ram@upi","amount":50000.00,"status":"FAILED","timestamp":"2026-04-08T16:00:00","ingested_at":"2026-04-08T16:00:01"}
{"transaction_id":105,"sender_upi_id":"ford@upi","receiver_upi_id":"test@upi","amount":3000.00,"status":"SUCCESS","timestamp":"2026-04-09T08:15:00","ingested_at":"2026-04-09T08:15:01"}
EOF

bq load --source_format=NEWLINE_DELIMITED_JSON \
  upi_analytics.transactions \
  /tmp/bq_test_data.json
```

### Verify

```bash
bq query --use_legacy_sql=false \
  'SELECT COUNT(*) AS total_rows FROM upi_analytics.transactions'
```

---

## Part 9: Cost & Usage

### Check query bytes processed

```bash
bq query --use_legacy_sql=false --dry_run \
  'SELECT * FROM upi_analytics.transactions'
```

This shows how many bytes the query would scan (you're charged per byte scanned).

### Check storage usage

```bash
bq show --format=prettyjson upi_analytics.transactions | jq '.numBytes, .numRows'
```

---

## Cleanup

```bash
# Delete the BigQuery dataset and all tables
bq rm -r -f $PROJECT_ID:upi_analytics

# Delete Cloud Run service
gcloud run services delete analytics-bq --region=$REGION --quiet

# Delete container image
gcloud container images delete gcr.io/$PROJECT_ID/analytics-bq --quiet
```

---

## Architecture Recap

```
  UPI Transfer Service
         |
    POST /api/transfer
         |
    Pub/Sub Topic
   (transaction-events)
         |
    analytics-sub
         |
  Analytics Service (Cloud Run)
    |              |
  In-Memory     BigQuery
   Stats      (upi_analytics.transactions)
    |              |
  GET /stats    SQL Queries / Dashboards
```

---

## Review Questions

1. **What is the difference between batch load and streaming insert?** When would you use each?
2. **Why do we use `--no-cpu-throttling` and `--min-instances=1`** for the analytics service?
3. **Streaming inserts are at-least-once.** How would you handle duplicates?
4. **How does BigQuery charge?** What's free? What costs money?
5. **What is columnar storage?** Why is it better for analytics than row storage?
6. **How would you partition the transactions table?** What benefit does it provide?

---

## What's Next

- **GKE**: Deploy all services to Google Kubernetes Engine
