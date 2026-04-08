# Lab 06: Logging, Debugging & Cleanup

**Duration:** 35 minutes
**Objective:** Use Cloud Logging to search, filter, and analyze application logs; create log-based metrics; add structured logging; and clean up all resources.

---

## Prerequisites

- Labs 01-05 completed (UPI service on Cloud Run with traffic history)

---

## Part 1: Explore Cloud Logging

### Navigate to logs

1. Go to **Navigation Menu > Logging > Logs Explorer**
2. You'll see the query builder at the top and log entries below

### View Cloud Run logs

Enter this query in the query builder:

```
resource.type="cloud_run_revision"
resource.labels.service_name="upi-transfer-service"
```

Click **Run query**. You should see:

| Log Type | Example |
|----------|---------|
| **Spring Boot startup** | `Started UpiTransferServiceApplication in 3.2 seconds` |
| **HTTP request logs** | `GET 200 /api/balance/nag@upi` |
| **Application logs** | `Published event to transaction-events...` |
| **Error logs** | Stack traces for failed requests |

---

## Part 2: Log Filters and Queries

### Filter by severity

```
resource.type="cloud_run_revision"
resource.labels.service_name="upi-transfer-service"
severity=ERROR
```

### Filter by time range

```
resource.type="cloud_run_revision"
resource.labels.service_name="upi-transfer-service"
severity>=WARNING
timestamp>="2026-04-08T00:00:00Z"
```

### Search in log text

```
resource.type="cloud_run_revision"
resource.labels.service_name="upi-transfer-service"
textPayload=~"transfer"
```

### Combine filters

```
resource.type="cloud_run_revision"
resource.labels.service_name="upi-transfer-service"
severity=ERROR
textPayload=~"Exception"
```

> The `=~` operator does a regex match. Use `=` for exact match.

### Using gcloud CLI

```bash
# View recent error logs
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="upi-transfer-service" AND severity=ERROR' \
  --limit=10 \
  --format="table(timestamp, severity, textPayload)"

# View request logs
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="upi-transfer-service" AND httpRequest.requestUrl=~"/api/transfer"' \
  --limit=10 \
  --format="table(timestamp, httpRequest.status, httpRequest.requestUrl, httpRequest.latency)"
```

---

## Part 3: Create a Log-Based Metric

### Count 500 errors

1. Go to **Logging > Logs Explorer**
2. Enter this filter:

```
resource.type="cloud_run_revision"
resource.labels.service_name="upi-transfer-service"
httpRequest.status=500
```

3. Click **Actions > Create Metric**
4. Configure:

| Setting | Value |
|---------|-------|
| **Metric type** | Counter |
| **Name** | `upi-service-500-errors` |
| **Description** | Count of 500 errors on UPI Transfer Service |
| **Filter** | (pre-filled from your query) |

5. Click **Create Metric**

### Use the metric

1. Go to **Monitoring > Metrics Explorer**
2. Search for your custom metric: `logging/user/upi-service-500-errors`
3. You can now chart this metric and create alerts on it

> Log-based metrics turn log patterns into charts and alerts — no code changes needed.

---

## Part 4: Add Structured JSON Logging

### Add dependency to pom.xml

```xml
<!-- Add to pom.xml dependencies -->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.0</version>
</dependency>
```

### Create logback-spring.xml

Create `src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- Console output for local development -->
    <springProfile name="default">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="CONSOLE" />
        </root>
    </springProfile>

    <!-- JSON output for Cloud Run -->
    <springProfile name="gcp">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <fieldNames>
                    <timestamp>timestamp</timestamp>
                    <levelValue>[ignore]</levelValue>
                    <version>[ignore]</version>
                </fieldNames>
                <throwableConverter class="net.logstash.logback.stacktrace.ShortenedThrowableConverter">
                    <maxDepthPerThrowable>5</maxDepthPerThrowable>
                </throwableConverter>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON" />
        </root>
    </springProfile>
</configuration>
```

### Activate the GCP profile on Cloud Run

Add to `application.properties`:

```properties
# Activate 'gcp' profile when GOOGLE_CLOUD_PROJECT is set (auto-set on Cloud Run)
spring.profiles.active=${GCP_PROFILE:default}
```

When deploying to Cloud Run, set the environment variable:

```bash
gcloud run services update upi-transfer-service \
  --region=asia-south1 \
  --set-env-vars="GCP_PROFILE=gcp"
```

### Add structured fields to log messages

In your TransferService or controller, log with key-value pairs:

```java
import org.slf4j.MDC;

// Before processing a transfer
MDC.put("txnId", request.getSenderUpiId() + "-" + System.currentTimeMillis());
MDC.put("fromVpa", request.getSenderUpiId());
MDC.put("toVpa", request.getReceiverUpiId());
MDC.put("amount", request.getAmount().toString());

log.info("Transfer initiated");

// ... after processing
log.info("Transfer completed with status: {}", status);

MDC.clear();
```

### What Cloud Logging receives

Instead of plain text, Cloud Logging now gets structured JSON:

```json
{
  "timestamp": "2026-04-08T10:30:45.123Z",
  "level": "INFO",
  "message": "Transfer initiated",
  "txnId": "nag@upi-1712571045123",
  "fromVpa": "nag@upi",
  "toVpa": "ram@upi",
  "amount": "500"
}
```

> Every field becomes searchable in Cloud Logging: `jsonPayload.fromVpa="nag@upi"`

---

## Part 5: View Structured Logs

After deploying the updated service:

```bash
# Query structured log fields
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="upi-transfer-service" AND jsonPayload.message="Transfer initiated"' \
  --limit=5 \
  --format=json
```

In the Console:

1. Go to **Logging > Logs Explorer**
2. Query: `jsonPayload.fromVpa="nag@upi"`
3. You should see only logs related to transfers from `nag@upi`

---

## Part 6: Cloud Trace (Overview)

### View traces

1. Go to **Navigation Menu > Trace > Trace Explorer**
2. Select the time range covering your test traffic
3. You should see request traces for your Cloud Run service

### What to look for

| Column | Meaning |
|--------|---------|
| **URI** | The endpoint called |
| **Latency** | Total request time |
| **Method** | HTTP method |
| **Status** | Response code |

4. Click on a trace to see the **span waterfall** — how time was spent within the request

> Cloud Run automatically generates basic traces. For deeper tracing (DB queries, Pub/Sub calls), add the `spring-cloud-gcp-starter-trace` dependency.

---

## Part 7: Resource Cleanup

### Review what we created in Day 35 + Day 36

| Resource | Service | Created In |
|----------|---------|-----------|
| Cloud Run service | `upi-transfer-service` | Day 35 |
| Artifact Registry repo | `upi-repo` | Day 35 |
| Docker images | Multiple tags | Day 35 + 36 |
| Pub/Sub topic | `transaction-events` | Day 36 |
| Pub/Sub subscription | `transaction-processor` | Day 36 |
| Cloud Build trigger | `upi-service-deploy` | Day 36 |
| GKE cluster | `tekton-cluster` | Day 36 |
| Monitoring dashboard | `UPI Transfer Service - Production` | Day 36 |
| Alert policies | Error rate + Uptime check | Day 36 |
| Uptime check | `/actuator/health` | Day 36 |
| Log-based metric | `upi-service-500-errors` | Day 36 |

### Cleanup commands

> **Only run these if you want to remove all resources.** If Day 37 labs continue from here, skip cleanup.

```bash
# Delete Cloud Run service
gcloud run services delete upi-transfer-service --region=asia-south1 --quiet

# Delete Pub/Sub resources
gcloud pubsub subscriptions delete transaction-processor --quiet
gcloud pubsub topics delete transaction-events --quiet

# Delete GKE cluster (Tekton) — this takes a few minutes
gcloud container clusters delete tekton-cluster --region=asia-south1 --quiet

# Delete Artifact Registry images (keep the repo for Day 37)
gcloud artifacts docker images list \
  asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo \
  --format='value(package)' | while read img; do
    gcloud artifacts docker images delete "$img" --quiet --delete-tags 2>/dev/null
done

# Delete Cloud Build trigger
gcloud builds triggers list --format='value(name)' | while read trigger; do
  gcloud builds triggers delete "$trigger" --quiet
done

# Delete alert policies
gcloud alpha monitoring policies list --format='value(name)' | while read policy; do
  gcloud alpha monitoring policies delete "$policy" --quiet
done

# Delete uptime checks
gcloud monitoring uptime list-configs --format='value(name)' | while read check; do
  gcloud monitoring uptime delete "$check" --quiet
done

echo "Cleanup complete!"
```

### Verify cleanup

```bash
echo "=== Cloud Run ==="
gcloud run services list --region=asia-south1

echo "=== Pub/Sub Topics ==="
gcloud pubsub topics list

echo "=== GKE Clusters ==="
gcloud container clusters list

echo "=== Remaining costs ==="
echo "Check: https://console.cloud.google.com/billing"
```

---

## Checkpoint

- [ ] Explored Cloud Logging with filters (severity, time, text search)
- [ ] Used both Console and gcloud CLI for log queries
- [ ] Created a log-based metric for 500 errors
- [ ] Added structured JSON logging with logback
- [ ] Verified structured fields are searchable in Cloud Logging
- [ ] Explored Cloud Trace for request latency analysis
- [ ] Reviewed all resources created in Day 35 + Day 36
- [ ] (Optional) Ran cleanup commands to remove resources

---

## Day 36 Summary

Today you learned the complete DevOps toolkit:

| Topic | What You Did |
|-------|-------------|
| **CI/CD** | Built automated pipelines with Cloud Build and Tekton |
| **Messaging** | Created Pub/Sub topics, published/consumed events in Spring Boot |
| **Monitoring** | Built dashboards, set up alerts, created uptime checks |
| **Logging** | Searched logs, created log-based metrics, added structured logging |

**Your UPI Transfer Service is now automated, event-driven, and fully observable.**
