# Lab 12: GCP Memorystore — Redis Caching & Rate Limiting

## Objectives
- Provision GCP Memorystore (managed Redis)
- Create a VPC Connector for Cloud Run
- Deploy upi-transfer-service-redis to Cloud Run with Memorystore
- Test caching (cache hit vs miss) and rate limiting on GCP
- (Optional) Local dev with Docker for quick iteration

---

## Prerequisites
- GCP project with billing enabled
- `gcloud` CLI authenticated
- Docker (for building container image)
- `upi-transfer-service-redis` project

---

## Part 1: Create a VPC Network

Memorystore only has a private IP — it lives inside your VPC. Cloud Run needs a VPC Connector to reach it.

```bash
# Set your project
export PROJECT_ID=$(gcloud config get-value project)
export REGION=us-central1

# Ensure Compute API is enabled (needed for networking)
gcloud services enable compute.googleapis.com
gcloud services enable redis.googleapis.com
gcloud services enable vpcaccess.googleapis.com
gcloud services enable run.googleapis.com
```

---

## Part 2: Create Memorystore Instance

```bash
# Create a 1GB Basic tier Redis instance
gcloud redis instances create upi-cache \
  --size=1 \
  --region=$REGION \
  --redis-version=redis_7_0 \
  --tier=BASIC

# This takes 3-5 minutes...
```

### Get the Redis IP

```bash
export REDIS_HOST=$(gcloud redis instances describe upi-cache \
  --region=$REGION \
  --format="value(host)")

echo "Memorystore IP: $REDIS_HOST"
```

### Verify from Cloud Shell (same VPC)

```bash
# Install redis-cli if needed
sudo apt-get install redis-tools -y

# Test connection
redis-cli -h $REDIS_HOST PING
# Expected: PONG

# Try some commands
redis-cli -h $REDIS_HOST SET test "hello-from-memorystore"
redis-cli -h $REDIS_HOST GET test
redis-cli -h $REDIS_HOST DEL test
```

---

## Part 3: Create VPC Connector

Cloud Run is serverless — it doesn't live in your VPC by default. A VPC Connector bridges Cloud Run to Memorystore.

```bash
# Create the connector
gcloud compute networks vpc-access connectors create upi-connector \
  --region=$REGION \
  --range=10.8.0.0/28

# Verify
gcloud compute networks vpc-access connectors describe upi-connector \
  --region=$REGION
```

---

## Part 4: Build & Push Container Image

```bash
cd upi-transfer-service-redis

# Build with Cloud Build (no local Docker needed)
gcloud builds submit --tag gcr.io/$PROJECT_ID/upi-service-redis

# Or build locally and push
docker build -t gcr.io/$PROJECT_ID/upi-service-redis .
docker push gcr.io/$PROJECT_ID/upi-service-redis
```

---

## Part 5: Deploy to Cloud Run with Memorystore

```bash
gcloud run deploy upi-service-redis \
  --image=gcr.io/$PROJECT_ID/upi-service-redis \
  --region=$REGION \
  --platform=managed \
  --allow-unauthenticated \
  --vpc-connector=upi-connector \
  --set-env-vars="REDIS_HOST=$REDIS_HOST,REDIS_PORT=6379,RATE_LIMIT_MAX=10,RATE_LIMIT_WINDOW=60" \
  --memory=512Mi \
  --port=8080
```

### Get the service URL

```bash
export SERVICE_URL=$(gcloud run services describe upi-service-redis \
  --region=$REGION \
  --format="value(status.url)")

echo "Service URL: $SERVICE_URL"
```

---

## Part 6: Test Caching on GCP

### Step 1 — First balance check (CACHE MISS)

```bash
curl -s $SERVICE_URL/api/balance/nag@upi | jq
```

**Response includes `"cachedResult": false`** — first call, nothing in Memorystore yet.

### Step 2 — Second balance check (CACHE HIT)

```bash
curl -s $SERVICE_URL/api/balance/nag@upi | jq
```

**Response now shows `"cachedResult": true`** — served from Memorystore!

### Step 3 — Verify in Memorystore directly

```bash
# From Cloud Shell (same VPC):
redis-cli -h $REDIS_HOST

# Inside Redis CLI:
GET account:nag@upi
TTL account:nag@upi
KEYS account:*
```

---

## Part 7: Test Cache Eviction on GCP

### Step 1 — Make a transfer

```bash
curl -s -X POST $SERVICE_URL/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":100,"remark":"gcp-test"}' | jq
```

### Step 2 — Check balance (should be CACHE MISS after eviction)

```bash
curl -s $SERVICE_URL/api/balance/nag@upi | jq
```

The transfer evicted the cache — this is a fresh DB read (`"cachedResult": false`).

### Step 3 — Check again (CACHE HIT)

```bash
curl -s $SERVICE_URL/api/balance/nag@upi | jq
```

Now it's cached again (`"cachedResult": true`).

---

## Part 8: Test Rate Limiting on GCP

### Step 1 — Check current rate limit

```bash
curl -s $SERVICE_URL/api/rate-limit/nag@upi | jq
```

### Step 2 — Fire 11 rapid transfers

```bash
for i in $(seq 1 11); do
  echo "Transfer #$i:"
  curl -s -X POST $SERVICE_URL/api/transfer \
    -H "Content-Type: application/json" \
    -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":1,"remark":"rate-test-'$i'"}' | jq '.status // .error'
  echo "---"
done
```

**Expected:** First 10 succeed, 11th returns:
```json
{"error": "Rate limit exceeded. Max 10 transfers per minute for: nag@upi"}
```

### Step 3 — Watch the counter in Memorystore

```bash
redis-cli -h $REDIS_HOST GET ratelimit:transfer:nag@upi
redis-cli -h $REDIS_HOST TTL ratelimit:transfer:nag@upi
```

### Step 4 — Wait 60 seconds, try again

The key auto-expires. After 60s, the user can transfer again.

---

## Part 9: View Memorystore Metrics

### In Google Cloud Console

1. Go to **Memorystore > Redis**
2. Click on **upi-cache**
3. View **Monitoring** tab:
   - Memory usage
   - Connected clients
   - Cache hit ratio
   - Commands per second

### Via CLI

```bash
# Instance info
gcloud redis instances describe upi-cache --region=$REGION

# Check memory usage from Redis
redis-cli -h $REDIS_HOST INFO memory
redis-cli -h $REDIS_HOST INFO stats
```

---

## Part 10: Explore Redis Commands

From Cloud Shell, connect to Memorystore and explore:

```bash
redis-cli -h $REDIS_HOST

# See all keys
KEYS *

# Check memory usage per key
MEMORY USAGE account:nag@upi

# Monitor all commands in real-time
MONITOR
# (Hit the API in another terminal and watch commands fly by)
# Press Ctrl+C to stop

# Check server info
INFO server
INFO clients
INFO keyspace
```

---

## Optional: Local Development with Docker

For quick iteration before deploying to GCP:

```bash
cd upi-transfer-service-redis

# Start local Redis
docker run -d --name redis -p 6379:6379 redis:7-alpine

# Run the app
./mvnw spring-boot:run

# Test locally
curl -s http://localhost:8080/api/balance/nag@upi | jq

# Or use Docker Compose for both
docker compose up --build
```

---

## Cleanup

```bash
# Delete Cloud Run service
gcloud run services delete upi-service-redis --region=$REGION --quiet

# Delete VPC Connector
gcloud compute networks vpc-access connectors delete upi-connector \
  --region=$REGION --quiet

# Delete Memorystore instance
gcloud redis instances delete upi-cache --region=$REGION --quiet

# Delete container image
gcloud container images delete gcr.io/$PROJECT_ID/upi-service-redis --quiet
```

---

## Architecture Recap

```
                    Internet
                       |
                  Cloud Run
              (upi-service-redis)
                    /      \
            VPC Connector    H2 (in-memory DB)
                 |
            Memorystore
          (Redis - private IP)
```

- **Cloud Run** handles HTTP traffic (auto-scales)
- **VPC Connector** bridges Cloud Run into the VPC
- **Memorystore** provides sub-ms caching & rate limiting (private IP only)
- **H2** is still the database (swap for Cloud SQL in production)

---

## Review Questions

1. **Why does Memorystore have no public IP?** What security benefit does this provide?
2. **What is a VPC Connector?** Why does Cloud Run need one to reach Memorystore?
3. **What happens if Memorystore goes down?** Does the app crash or degrade gracefully?
4. **Why do we evict cache on transfer** instead of updating it?
5. **Basic vs Standard tier** — when would you choose Standard?
6. **What eviction policy should you use** for a caching workload? Why `allkeys-lru`?

---

## What's Next

- **BigQuery**: Stream transaction data to BigQuery for analytics
- **GKE**: Deploy all services to Kubernetes
