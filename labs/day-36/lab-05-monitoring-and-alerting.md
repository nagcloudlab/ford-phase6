# Lab 05: Monitoring and Alerting

**Duration:** 35 minutes
**Objective:** Explore Cloud Monitoring dashboards, create custom dashboards for the UPI service, and set up alert policies.

---

## Prerequisites

- UPI Transfer Service running on Cloud Run
- Some traffic history from previous labs

---

## Part 1: Explore Built-in Cloud Run Metrics

### Navigate to Monitoring

1. Go to **Navigation Menu > Monitoring > Overview**
2. First time? Click **Get started** to set up a workspace (auto-created for your project)

### Cloud Run Metrics

1. Go to **Cloud Run > upi-transfer-service > Metrics**
2. Explore each chart:

| Metric | What It Tells You |
|--------|------------------|
| **Request count** | Total requests over time |
| **Request latency** | p50, p95, p99 response times |
| **Container instance count** | How many instances are running |
| **CPU utilization** | Load on each instance |
| **Memory utilization** | How close to memory limits |
| **Billable instance time** | What you're actually paying for |

### Generate traffic to populate charts

```bash
export SERVICE_URL=$(gcloud run services describe upi-transfer-service \
  --region asia-south1 --format='value(status.url)')

# Generate a mix of successful and error requests
for i in $(seq 1 50); do
  curl -s $SERVICE_URL/api/balance/nag@upi > /dev/null
  curl -s $SERVICE_URL/api/balance/ram@upi > /dev/null
  curl -s -X POST $SERVICE_URL/api/transfer \
    -H "Content-Type: application/json" \
    -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":10}' > /dev/null
  curl -s $SERVICE_URL/api/balance/nobody@upi > /dev/null  # May cause errors
done
echo "Traffic generated — check metrics in ~1-2 minutes"
```

> Metrics take 1-2 minutes to appear. Refresh the page.

---

## Part 2: Metrics Explorer

### Navigate to Metrics Explorer

1. Go to **Monitoring > Metrics Explorer**
2. Click **Select a metric**

### Query 1: Request Latency by Status Code

| Setting | Value |
|---------|-------|
| Resource type | `Cloud Run Revision` |
| Metric | `Request Latencies` |
| Filter | `service_name = upi-transfer-service` |
| Group by | `response_code` |
| Aggregation | `95th percentile` |

You should see separate lines for 200, 404, 500 responses with their p95 latency.

### Query 2: Request Count Over Time

| Setting | Value |
|---------|-------|
| Resource type | `Cloud Run Revision` |
| Metric | `Request Count` |
| Filter | `service_name = upi-transfer-service` |
| Group by | `response_code_class` |
| Aggregation | `Sum` |

This shows 2xx vs 4xx vs 5xx request volumes.

### Query 3: Instance Count

| Setting | Value |
|---------|-------|
| Resource type | `Cloud Run Revision` |
| Metric | `Container Instance Count` |
| Aggregation | `Max` |

This shows scaling behavior — how many instances were running at any point.

---

## Part 3: Create a Custom Dashboard

### Create the dashboard

1. Go to **Monitoring > Dashboards**
2. Click **Create Dashboard**
3. Name it: `UPI Transfer Service - Production`

### Add widgets

Click **Add Widget** for each:

**Widget 1: Request Rate**
- Type: Line chart
- Metric: Cloud Run Revision > Request Count
- Filter: service_name = upi-transfer-service
- Aggregation: Rate (per second)

**Widget 2: Error Rate**
- Type: Line chart
- Metric: Cloud Run Revision > Request Count
- Filter: service_name = upi-transfer-service, response_code_class = 5xx
- Aggregation: Sum

**Widget 3: p95 Latency**
- Type: Line chart
- Metric: Cloud Run Revision > Request Latencies
- Filter: service_name = upi-transfer-service
- Aggregation: 95th percentile

**Widget 4: Active Instances**
- Type: Line chart
- Metric: Cloud Run Revision > Container Instance Count
- Aggregation: Max

### Arrange the layout

Drag widgets to create a 2x2 grid. Your dashboard should give you a single-glance view of service health.

> Save the dashboard — you'll use it in the next section to verify alerts.

---

## Part 4: Set Up Notification Channels

### Create an email notification channel

1. Go to **Monitoring > Alerting > Edit Notification Channels**
2. Under **Email**, click **Add New**
3. Enter your email address
4. Click **Save**

### Verify

You should see your email listed under active notification channels.

---

## Part 5: Create Alert Policies

### Alert 1: High Error Rate

1. Go to **Monitoring > Alerting > Create Policy**
2. **Add Condition:**

| Setting | Value |
|---------|-------|
| Resource type | Cloud Run Revision |
| Metric | Request Count |
| Filter | service_name = upi-transfer-service, response_code_class = 5xx |
| Rolling window | 5 minutes |
| Condition | Threshold > 5 |

3. **Notification channel:** Select your email
4. **Name:** `UPI Service - High Error Rate`
5. **Documentation:** Add: "Check Cloud Logging for 5xx errors. Dashboard: UPI Transfer Service - Production"
6. Click **Create Policy**

### Alert 2: Uptime Check

1. Go to **Monitoring > Uptime Checks > Create Uptime Check**
2. Configure:

| Setting | Value |
|---------|-------|
| Protocol | HTTPS |
| Hostname | Your Cloud Run URL (without https://) |
| Path | `/actuator/health` |
| Check frequency | Every 1 minute |
| Regions | All available |
| Response validation | Status code = 200 |

3. Create an alert policy for this uptime check:
   - Alert if check fails from **2 or more regions**
   - Notification channel: your email
   - Name: `UPI Service - Uptime Check`

---

## Part 6: Test the Alerts

### Generate errors to trigger the error rate alert

```bash
export SERVICE_URL=$(gcloud run services describe upi-transfer-service \
  --region asia-south1 --format='value(status.url)')

# Send requests to a non-existent endpoint that triggers 500s
for i in $(seq 1 20); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST $SERVICE_URL/api/transfer \
    -H "Content-Type: application/json" \
    -d '{"senderUpiId":"nobody@upi","receiverUpiId":"also-nobody@upi","amount":999999}'
done
```

### Check alert status

1. Go to **Monitoring > Alerting**
2. Look for **Open incidents** — your error rate alert should fire within 5 minutes
3. Check your email for the notification

### View incident details

1. Click on the open incident
2. You'll see:
   - When it started
   - The metric that triggered it
   - A link to the relevant chart

> Once errors stop, the incident will auto-resolve after the condition clears.

---

## Checkpoint

- [ ] Explored Cloud Run built-in metrics (request count, latency, instances)
- [ ] Used Metrics Explorer to query specific metrics with filters
- [ ] Created a custom dashboard with 4 widgets (request rate, errors, latency, instances)
- [ ] Set up email notification channel
- [ ] Created alert policy for high error rate
- [ ] Created uptime check with alert
- [ ] Generated errors and verified alert fires
