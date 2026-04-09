# Lab 08: Monitoring, Logging & Cleanup

**Duration:** 35 minutes
**Objective:** Use Cloud Run's built-in monitoring and logging to observe your app in real-time, understand health checks, and clean up all resources.

---

## Prerequisites

- Lab 07 completed (service running with traffic history)

---

## Part 1: Cloud Run Metrics Dashboard

1. Go to **Cloud Run > upi-transfer-service > Metrics**
2. Explore each chart:

| Metric | What It Tells You |
|--------|------------------|
| **Request count** | Total requests over time — is traffic growing? |
| **Request latency** | p50, p95, p99 response times — are users happy? |
| **Container instance count** | How many instances are running — scaling behavior |
| **CPU utilization** | Are instances under heavy load? |
| **Memory utilization** | Is the app close to its memory limit? |
| **Billable container instance time** | What you're actually paying for |

### Generate traffic to populate charts

```bash
export SERVICE_URL=$(gcloud run services describe upi-transfer-service \
  --region asia-south1 --format='value(status.url)')

# Mix of success and error requests
for i in $(seq 1 30); do
  curl -s $SERVICE_URL/api/balance/nag@upi > /dev/null
  curl -s $SERVICE_URL/api/balance/ram@upi > /dev/null
  curl -s $SERVICE_URL/api/balance/nobody@upi > /dev/null  # This will 500
done
echo "Traffic generated — check metrics in ~1 minute"
```

> Metrics take 1-2 minutes to appear. Refresh the page.

---

## Part 2: Application Logs

### View logs in Console

1. Go to **Cloud Run > upi-transfer-service > Logs**
2. You'll see:
   - Spring Boot startup logs
   - HTTP request logs (method, path, status code, latency)
   - Application logs (SQL queries, errors)

### Filter logs

Use the filter bar at the top:

| Filter | What It Shows |
|--------|-------------|
| `severity=ERROR` | Only error logs |
| `httpRequest.status=500` | Only failed requests |
| `textPayload:"transfer"` | Logs containing "transfer" |

### View logs via CLI

```bash
# Recent logs
gcloud run services logs read upi-transfer-service \
  --region=asia-south1 --limit=30

# Tail logs in real-time (Ctrl+C to stop)
gcloud run services logs tail upi-transfer-service \
  --region=asia-south1
```

### Generate an error and find it in logs

In a **separate terminal**, trigger an error:

```bash
# Invalid UPI ID — will throw an exception
curl -s $SERVICE_URL/api/balance/nobody@upi

# Insufficient balance
curl -s -X POST $SERVICE_URL/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "ram@upi",
    "receiverUpiId": "nag@upi",
    "amount": 999999,
    "remark": "error test"
  }'
```

Now find these errors in the Logs tab. Click on an error entry to see the full stack trace.

---

## Part 3: Health Checks

Cloud Run uses health checks to know if your container is alive and ready.

### Your app already has one

```bash
curl -s $SERVICE_URL/actuator/health | python3 -m json.tool
```

```json
{
    "status": "UP",
    "components": {
        "db": {
            "status": "UP",
            "details": {
                "database": "H2",
                "validationQuery": "isValid()"
            }
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

### How Cloud Run uses this

| Check Type | Purpose | Default |
|-----------|---------|---------|
| **Startup probe** | Is the app ready to serve? | Wait for container to start |
| **Liveness probe** | Is the app still healthy? | Auto-restart if unhealthy |

### Configure a startup probe (optional but recommended)

```bash
gcloud run services update upi-transfer-service \
  --region=asia-south1 \
  --startup-cpu-boost \
  --cpu-throttling
```

> `--startup-cpu-boost` gives extra CPU during startup — helps Java/Spring Boot start faster.

---

## Part 4: Cloud Logging (Logs Explorer)

For more powerful log analysis, use the full **Logs Explorer**.

1. Go to **Navigation Menu > Logging > Logs Explorer**
2. In the query builder, enter:

```
resource.type="cloud_run_revision"
resource.labels.service_name="upi-transfer-service"
```

3. Click **Run Query**

### Useful queries

**All errors:**
```
resource.type="cloud_run_revision"
resource.labels.service_name="upi-transfer-service"
severity>=ERROR
```

**Requests slower than 1 second:**
```
resource.type="cloud_run_revision"
resource.labels.service_name="upi-transfer-service"
httpRequest.latency>"1s"
```

**Specific text in logs:**
```
resource.type="cloud_run_revision"
resource.labels.service_name="upi-transfer-service"
textPayload=~"transfer"
```

### Create a log-based alert (optional)

1. In Logs Explorer, after running a query for errors
2. Click **Create Alert**
3. Set alert name: `upi-service-errors`
4. Notification channel: your email
5. This will email you whenever an error occurs

---

## Part 5: Cloud Monitoring Dashboard (Optional)

1. Go to **Navigation Menu > Monitoring > Dashboards**
2. Click **+ Create Dashboard**
3. Name it `UPI Service Health`
4. Add charts:
   - **Cloud Run Request Count** (line chart)
   - **Cloud Run Request Latency** (line chart, p95)
   - **Instance Count** (line chart)
5. Save the dashboard

This gives you a **single view** of your service health — like what on-call teams watch in production.

---

## Part 6: Cleanup — Remove All Resources

> Always clean up workshop resources to avoid charges. Even with free tier, it's good practice.

### Step 1: Delete Cloud Run service

```bash
gcloud run services delete upi-transfer-service \
  --region=asia-south1 --quiet

# Verify
gcloud run services list --region=asia-south1
```

### Step 2: Delete images from Artifact Registry

```bash
export PROJECT_ID=$(gcloud config get-value project)

# Delete all image tags
gcloud artifacts docker images delete \
  asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service \
  --delete-tags --quiet

# Delete the repository
gcloud artifacts repositories delete upi-repo \
  --location=asia-south1 --quiet

# Verify
gcloud artifacts repositories list --location=asia-south1
```

### Step 3: Delete firewall rule (from Lab 04, if not already deleted)

```bash
gcloud compute firewall-rules delete allow-upi-app --quiet 2>/dev/null
echo "Firewall rule cleaned up (or was already deleted)"
```

### Step 4: Verify no billable resources remain

```bash
echo "=== Cleanup Verification ==="
echo "Cloud Run services:"
gcloud run services list --region=asia-south1 2>/dev/null || echo "  None"
echo ""
echo "Compute instances:"
gcloud compute instances list 2>/dev/null || echo "  None"
echo ""
echo "Artifact Registry repos:"
gcloud artifacts repositories list --location=asia-south1 2>/dev/null || echo "  None"
echo ""
echo "Firewall rules (custom):"
gcloud compute firewall-rules list --filter="name:allow-" 2>/dev/null || echo "  None"
echo ""
echo "Cleanup complete!"
```

### Alternative: Keep for Day 36

If continuing to Day 36 tomorrow, **skip cleanup** but ensure:

```bash
# Scale to zero so you pay nothing overnight
gcloud run services update upi-transfer-service \
  --region=asia-south1 --min-instances=0
```

---

## Checkpoint

- [ ] Explored Cloud Run Metrics (requests, latency, instance count)
- [ ] Viewed application logs in Console and CLI
- [ ] Generated errors and found them in logs
- [ ] Verified the `/actuator/health` endpoint
- [ ] Explored Logs Explorer with query filters
- [ ] Cleaned up all resources (or kept them for Day 36)

---

## Day 35 Complete — The Full Journey

| Lab | What You Did | Key Lesson |
|-----|-------------|-----------|
| **01** | Created GCP account, activated free tier | The platform and free tier |
| **02** | Set up project, billing alerts, explored IAM | Security and cost control from day one |
| **03** | Cloud Shell, gcloud CLI, built and ran app locally | Your dev environment in the browser |
| **04** | Deployed on a VM manually | **The pain of managing servers** |
| **05** | Containerized the app, pushed to Artifact Registry | Build once, run anywhere |
| **06** | Deployed to Cloud Run — one command, live URL | **The power of managed services** |
| **07** | Scaling, load balancing proof, traffic splitting | Production-grade deployment |
| **08** | Monitoring, logging, health checks, cleanup | Observability and housekeeping |

### The Arc of the Day

```
Manual VM deployment (painful, 45 min, no scaling, no HTTPS)
                          │
                          ▼
            "There must be a better way"
                          │
                          ▼
Cloud Run deployment (1 command, 30 sec, auto-scaling, free HTTPS)
                          │
                          ▼
        Load balancing + traffic splitting + monitoring
                          │
                          ▼
               Production-ready deployment
```

### Tomorrow (Day 36)

- **Tekton Pipelines** — automate the build → push → deploy cycle
- **Google Pub/Sub** — add event-driven messaging to the UPI app
- **Cloud Monitoring** — structured logging and alerts
