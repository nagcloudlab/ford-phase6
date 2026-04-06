# Lab 07: Scaling, Load Balancing & Traffic Splitting

**Duration:** 40 minutes
**Objective:** Observe Cloud Run auto-scaling under load, prove load balancing using the servedBy response field, and learn traffic splitting for canary deployments.

---

## Prerequisites

- Lab 06 completed (two revisions deployed, service live)

---

## Part 1: Observe Scale-to-Zero

### Check current state

1. Go to **Cloud Run > upi-transfer-service > Metrics**
2. Look at the **Container instance count** chart
3. If you haven't made requests recently, it should be at **0**

### Trigger an instance

```bash
export SERVICE_URL=$(gcloud run services describe upi-transfer-service \
  --region asia-south1 --format='value(status.url)')

# First request after idle — measure the time
time curl -s $SERVICE_URL/api/balance/nag@upi > /dev/null
```

**First request takes 3-5 seconds** — this is the **cold start**. Cloud Run is:
1. Pulling the container image
2. Starting the JVM
3. Spring Boot initializing
4. Then serving the request

### Subsequent requests are fast

```bash
# Immediate follow-up — already warm
time curl -s $SERVICE_URL/api/balance/nag@upi > /dev/null
```

**~200-400ms** — the instance is warm.

### The trade-off

| Setting | Cost | Cold Start |
|---------|------|-----------|
| `--min-instances=0` | Free when idle | Yes (3-5s for Java) |
| `--min-instances=1` | Always paying for 1 instance | No cold start |

**For production:** set `--min-instances=1` to keep one instance warm.

---

## Part 2: Why Scaling Doesn't Happen Easily

Before we generate load, let's understand **why Cloud Run won't scale with simple requests**.

### The Concurrency Factor

Cloud Run's default **concurrency = 80**, meaning one instance can handle **80 simultaneous requests**. If you send requests one-by-one (sequential curl), a single instance handles all of them easily — no scaling needed.

```
Sequential requests:  req1 → done → req2 → done → req3 → done
                      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                      One instance handles everything. No scaling.

Concurrent requests:  req1 ──────▶
(concurrency = 1)     req2 ──────▶  ← needs NEW instance!
                      req3 ──────▶  ← needs NEW instance!
```

### The trick: Lower concurrency for the demo

```bash
# Set concurrency to 1 — forces a new instance per concurrent request
gcloud run services update upi-transfer-service \
  --region=asia-south1 \
  --concurrency=1
```

> In production you'd keep this at 80. We lower it to 1 for a clear demo.

---

## Part 3: Generate Load & Prove Load Balancing

This is where the `InstanceInfo` in the app proves its value.

### Step 1: Send concurrent requests

The `&` sends each curl to the **background**, so all 20 run at the same time:

```bash
echo "=== Sending 20 concurrent requests ==="
for i in $(seq 1 20); do
  curl -s $SERVICE_URL/api/balance/nag@upi -o /tmp/resp_$i.json &
done
wait
echo "All requests completed"
```

### Step 2: Read the results

```bash
echo ""
echo "=== Which instance served each request? ==="
for i in $(seq 1 20); do
  host=$(python3 -c "import json; d=json.load(open('/tmp/resp_$i.json')); print(d['servedBy']['hostAddress'])" 2>/dev/null)
  echo "Request $i → served by: $host"
done

# Count unique instances
echo ""
echo "=== Unique instances used ==="
for i in $(seq 1 20); do
  python3 -c "import json; print(json.load(open('/tmp/resp_$i.json'))['servedBy']['hostAddress'])" 2>/dev/null
done | sort | uniq -c | sort -rn
```

### What you should see

Requests are served by **different IP addresses**:

```
Request 1  → served by: 169.254.8.129
Request 2  → served by: 169.254.8.130
Request 3  → served by: 169.254.8.131
Request 4  → served by: 169.254.8.129
Request 5  → served by: 169.254.8.132
...

Unique instances used:
   6  169.254.8.129
   5  169.254.8.130
   5  169.254.8.131
   4  169.254.8.132
```

**Different IPs = different container instances = Cloud Run is load balancing across them.**

### Step 3: Watch it in the Console

1. Go to **Cloud Run > upi-transfer-service > Metrics**
2. Look at the **Container instance count** chart
3. You should see it spike to **multiple instances** during the load
4. After the load stops, watch it gradually **scale back down to 0**

### Step 4: Reset concurrency back to normal

```bash
gcloud run services update upi-transfer-service \
  --region=asia-south1 \
  --concurrency=80
```

### Why this matters

| Concurrency | Behavior | Use Case |
|-------------|----------|----------|
| 1 | New instance per concurrent request | Demo / CPU-heavy tasks |
| 80 (default) | One instance handles 80 requests | Most web APIs |
| 250 (max) | Fewer instances, higher utilization | High-throughput services |

**Tuning concurrency = tuning your cost vs performance trade-off.**

---

## Part 4: Traffic Splitting (Canary Deployment)

Traffic splitting lets you send a **percentage of traffic to different revisions** — perfect for testing a new version safely.

### List revisions

```bash
gcloud run revisions list \
  --service=upi-transfer-service \
  --region=asia-south1 \
  --format="table(REVISION, ACTIVE, PERCENT)"
```

You should see two revisions. Note their names.

### Split traffic: 70% to v1, 30% to v2

```bash
# Replace revision names with your actual names from the list above
gcloud run services update-traffic upi-transfer-service \
  --region=asia-south1 \
  --to-revisions=REVISION_V1_NAME=70,REVISION_V2_NAME=30
```

### Verify the split

```bash
gcloud run services describe upi-transfer-service \
  --region=asia-south1 \
  --format="yaml(status.traffic)"
```

### Test it

```bash
echo "=== Traffic Split Test ==="
for i in $(seq 1 20); do
  # v2 has 'test@upi', v1 doesn't
  # We check if test@upi exists to know which revision served us
  status=$(curl -s -o /dev/null -w "%{http_code}" $SERVICE_URL/api/balance/test@upi)
  if [ "$status" = "200" ]; then
    echo "Request $i → Revision V2 (test@upi exists)"
  else
    echo "Request $i → Revision V1 (test@upi not found)"
  fi
done
```

You should see roughly 70% hitting V1 and 30% hitting V2.

### Real-world use case

| Scenario | Traffic Split |
|----------|--------------|
| Testing a new feature | 95% old / 5% new |
| Gradually rolling out | 70% old / 30% new → 50/50 → 100% new |
| Instant rollback | 100% back to old revision |

---

## Part 5: Rollback

Something wrong with v2? Roll back in seconds:

```bash
# Send 100% to latest revision (v2)
gcloud run services update-traffic upi-transfer-service \
  --region=asia-south1 --to-latest

# OR roll back to v1 specifically
gcloud run services update-traffic upi-transfer-service \
  --region=asia-south1 \
  --to-revisions=REVISION_V1_NAME=100
```

### Zero downtime

| Action | Downtime |
|--------|----------|
| Deploy new revision | None |
| Traffic split | None |
| Rollback | None |

**Cloud Run never takes your app offline during deployments.**

---

## Part 6: Environment Variables & Configuration

### Set environment variables

```bash
gcloud run services update upi-transfer-service \
  --region=asia-south1 \
  --set-env-vars="APP_ENV=production,LOG_LEVEL=INFO"
```

### View configured env vars

```bash
gcloud run services describe upi-transfer-service \
  --region=asia-south1 \
  --format="yaml(spec.template.spec.containers[0].env)"
```

### Why this matters

| Approach | Risk |
|----------|------|
| Hardcode values in code | Must redeploy to change anything |
| Environment variables | Change config without rebuilding |
| Secret Manager (advanced) | For passwords, API keys — encrypted |

**Rule: Config belongs in env vars, secrets belong in Secret Manager. Never in code.**

---

## Checkpoint

- [ ] Observed scale-to-zero and cold start behavior
- [ ] Generated load and watched instances scale up
- [ ] **Proved load balancing** using the `servedBy` field (different IPs)
- [ ] Split traffic between two revisions (canary deployment)
- [ ] Rolled back to a previous revision with zero downtime
- [ ] Set environment variables on the service

---

## Key Takeaways

- **Auto-scaling is real** — Cloud Run scales from 0 to N instances based on traffic, no config needed
- **Cold start is the one trade-off** — mitigate with `--min-instances=1` in production
- **The servedBy field proves load balancing** — different requests, different instances
- **Traffic splitting = safe deployments** — test new versions with a small % of real traffic
- **Rollback is instant** — one command, zero downtime, no user impact
