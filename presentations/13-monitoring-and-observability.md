# Monitoring & Observability on GCP
## See Everything, Fix Faster

---

## Why Observability Matters
**"You can't fix what you can't see."**

### The Reality of Production
- Code that works in dev **breaks in production** in unexpected ways
- Users discover outages **before your team does**
- A 5-minute payment outage at scale = **thousands of failed UPI transfers**

### Without Observability
```
User: "Payments are failing!"
Team: "Let me SSH into the server and check logs..."
Manager: "How long has this been happening?"
Team: "...we don't know."
```

**Observability turns "we don't know" into "we already know and we're fixing it."**

---

## The Three Pillars of Observability

| Pillar | What It Answers | Example (UPI Service) |
|---|---|---|
| **Metrics** | How is the system performing right now? | Request latency is 450ms (up from 120ms) |
| **Logs** | What happened at a specific moment? | `ERROR: NPCI timeout for txn UPI-20260408-9832` |
| **Traces** | How did a request flow through the system? | API Gateway → Auth → Transfer Service → NPCI → DB |

### How They Work Together
```
Alert fires (Metric: error rate > 5%)
     ↓
Check dashboard (Metric: latency spike at 14:32)
     ↓
Search logs (Log: "Connection pool exhausted")
     ↓
Follow trace (Trace: DB call taking 8s instead of 50ms)
     ↓
Root cause found → Cloud SQL connection limit hit
```

**Metrics tell you SOMETHING is wrong. Logs tell you WHAT. Traces tell you WHERE.**

---

## Google Cloud Operations Suite
**Formerly known as Stackdriver — now fully integrated into GCP.**

| Tool | Purpose |
|---|---|
| **Cloud Monitoring** | Metrics, dashboards, uptime checks |
| **Cloud Logging** | Centralized log collection and analysis |
| **Cloud Trace** | Distributed tracing and latency analysis |
| **Cloud Profiler** | Continuous CPU and memory profiling |
| **Error Reporting** | Automatic error grouping and tracking |

### Key Advantage
- **Zero setup for GCP services** — Cloud Run, Cloud SQL, GKE emit metrics and logs automatically
- **Single pane of glass** — all data in one console
- **Deep integration** — logs link to traces, traces link to metrics

**You don't install agents or run separate servers. It's built into GCP.**

---

## Cloud Monitoring — Dashboards & Metrics

### What Gets Monitored Automatically
- Cloud Run: request count, latency, instance count, memory usage
- Cloud SQL: connections, queries, CPU, storage
- Load Balancer: request rate, error rate, backend latency

### Metrics Explorer
```
Resource type:  Cloud Run Revision
Metric:         Request Latencies
Filter:         service_name = "upi-transfer-service"
Group by:       response_code
Aggregation:    95th percentile
```

### Custom Metrics
When built-in metrics aren't enough, create your own:
```java
// Track UPI-specific business metrics
Meter registry → counter("upi.transfers.initiated")
Meter registry → counter("upi.transfers.completed")
Meter registry → timer("upi.npci.callback.latency")
```

**Dashboards turn raw numbers into visual understanding.**

---

## Cloud Logging — Your System's Black Box

### Structured vs Unstructured Logs
| Type | Example | Searchable? |
|---|---|---|
| **Unstructured** | `Payment failed for user 12345` | Only with text search |
| **Structured (JSON)** | `{"severity":"ERROR","userId":"12345","txnId":"UPI-9832","error":"TIMEOUT"}` | Every field is searchable |

### Log Filters
```
resource.type="cloud_run_revision"
resource.labels.service_name="upi-transfer-service"
severity=ERROR
jsonPayload.txnId="UPI-20260408-9832"
```

### Log Sinks — Route Logs Elsewhere
| Destination | Use Case |
|---|---|
| **BigQuery** | Long-term analysis, compliance audits |
| **Cloud Storage** | Archival, cost-effective retention |
| **Pub/Sub** | Real-time processing, SIEM integration |

### Log-Based Metrics
Turn log patterns into metrics you can alert on:
```
Filter: severity=ERROR AND jsonPayload.errorType="NPCI_TIMEOUT"
Metric: custom.googleapis.com/upi/npci_timeouts
```

**Structured logging is not optional. It's how you survive production.**

---

## Cloud Trace — Follow a Request End to End

### The Problem Traces Solve
```
User: "My UPI transfer is slow"
Team: "It could be the API, the auth service, the DB, or NPCI..."
```

### What a Trace Looks Like
```
POST /api/v1/transfers (total: 2450ms)
├── Authentication        [50ms]
├── Input Validation      [5ms]
├── Fraud Check           [120ms]
├── DB: Check Balance     [45ms]
├── NPCI API Call          [2100ms]  ← BOTTLENECK
├── DB: Update Status     [30ms]
└── Send Notification     [100ms]
```

### Trace Explorer
- View **latency distribution** across all requests
- Filter by **HTTP method, status code, URL pattern**
- Click any trace to see the **full span breakdown**
- **Auto-correlated** with logs — click a span to see its logs

**Traces answer: "Why is THIS request slow?" instead of guessing.**

---

## Alerting — Get Notified Before Users Complain

### Alert Policy Components
| Component | What It Does | Example |
|---|---|---|
| **Condition** | When to fire | Error rate > 5% for 5 minutes |
| **Notification Channel** | Where to send | Slack, email, PagerDuty, SMS |
| **Documentation** | What to do | Runbook link, escalation steps |

### Uptime Checks
```
Target:     https://upi-service-abc123.a.run.app/actuator/health
Frequency:  Every 60 seconds
Regions:    Mumbai, Singapore, Iowa
Alert if:   Fails from 2+ regions
```

### Essential Alerts for UPI Service
| Alert | Condition | Severity |
|---|---|---|
| High error rate | 5xx errors > 5% | Critical |
| High latency | p95 latency > 2s | Warning |
| Service down | Uptime check fails | Critical |
| DB connections exhausted | Active connections > 90% | Warning |
| Transfer failures | Custom metric > threshold | Critical |

**Good alerts wake you up for real problems. Bad alerts make you ignore all alerts.**

---

## Spring Boot Actuator + GCP Monitoring

### Actuator Endpoints
```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics, prometheus, info
  endpoint:
    health:
      show-details: when-authorized
```

### Key Endpoints
| Endpoint | Purpose | GCP Integration |
|---|---|---|
| `/actuator/health` | Liveness & readiness checks | Cloud Run health checks |
| `/actuator/prometheus` | Metrics in Prometheus format | Cloud Monitoring via sidecar |
| `/actuator/metrics` | JVM and app metrics | Custom metric export |
| `/actuator/info` | Build version, git commit | Deployment tracking |

### Health Check for Cloud Run
```java
@Component
public class NpciHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        boolean npciReachable = checkNpciConnectivity();
        if (npciReachable) {
            return Health.up()
                .withDetail("npci", "reachable")
                .build();
        }
        return Health.down()
            .withDetail("npci", "unreachable")
            .build();
    }
}
```

**Actuator gives GCP the hooks it needs to monitor your app from the inside.**

---

## Structured Logging in Spring Boot

### Dependencies
```xml
<dependency>
    <groupId>com.google.cloud</groupId>
    <artifactId>spring-cloud-gcp-starter-logging</artifactId>
</dependency>
```

### JSON Logging Configuration
```yaml
# application.yml
spring:
  cloud:
    gcp:
      logging:
        enabled: true
logging:
  level:
    com.example.upi: INFO
```

### Logging with Context
```java
@Slf4j
@Service
public class TransferService {

    public TransferResponse initiateTransfer(TransferRequest request) {
        log.info("Transfer initiated",
            kv("txnId", request.getTxnId()),
            kv("fromVpa", request.getFromVpa()),
            kv("toVpa", request.getToVpa()),
            kv("amount", request.getAmount()));

        // ... business logic

        log.info("Transfer completed",
            kv("txnId", request.getTxnId()),
            kv("status", "SUCCESS"),
            kv("latencyMs", elapsed));
    }
}
```

### What Cloud Logging Receives
```json
{
  "severity": "INFO",
  "message": "Transfer initiated",
  "txnId": "UPI-20260408-9832",
  "fromVpa": "user@okbank",
  "toVpa": "merchant@okbank",
  "amount": 500.00,
  "trace": "projects/ford-upi/traces/abc123def456",
  "spanId": "789xyz"
}
```

**Trace ID auto-attached to every log line — click a log, see the full trace.**

---

## Building a Dashboard for UPI Service

### The Four Golden Signals (Google SRE)
| Signal | Metric | Target |
|---|---|---|
| **Latency** | p50, p95, p99 response time | p95 < 500ms |
| **Traffic** | Requests per second | Monitor trends |
| **Errors** | 5xx rate as % of total | < 0.1% |
| **Saturation** | CPU, memory, DB connections | < 80% utilization |

### Dashboard Layout
```
┌─────────────────────────────────────────────────┐
│  UPI Transfer Service — Production Dashboard    │
├────────────────────┬────────────────────────────┤
│  Request Rate      │  Error Rate (%)            │
│  ████████▓▓░░      │  ▁▁▁▁▁▁▁▁▃█▁▁             │
├────────────────────┼────────────────────────────┤
│  p95 Latency (ms)  │  Active Instances          │
│  ▁▁▂▂▃▃▂▂▁▁▂▂      │  ██████░░░░                │
├────────────────────┼────────────────────────────┤
│  Transfer Success   │  JVM Heap Usage            │
│  Rate (%)          │  ████████░░                 │
├────────────────────┼────────────────────────────┤
│  DB Connection Pool │  NPCI Response Time        │
│  ██████▓▓░░        │  ▁▁▁▁▂▂███▂▁               │
└────────────────────┴────────────────────────────┘
```

**One glance should tell you: "Is the system healthy or not?"**

---

## Incident Response Workflow

### Real Scenario: UPI Transfers Failing
```
14:32  ALERT: Error rate > 5% on upi-transfer-service
         ↓
14:33  CHECK DASHBOARD: Error spike started at 14:30
       Latency also spiking. Instance count normal.
         ↓
14:35  SEARCH LOGS:
       Filter: severity=ERROR AND timestamp>="2026-04-08T14:30:00Z"
       Result: "java.sql.SQLTransientConnectionException:
               HikariPool - Connection is not available"
         ↓
14:37  CHECK TRACE: DB spans showing 30s timeout
       All other spans normal.
         ↓
14:38  ROOT CAUSE: Cloud SQL max connections reached.
       A rogue query is holding connections open.
         ↓
14:40  FIX: Kill stuck queries, increase pool timeout,
       deploy connection pool limit fix.
         ↓
14:45  VERIFY: Dashboard shows error rate back to 0%.
       Write post-incident report.
```

### Total time to resolution: 13 minutes
**Without observability, this could take hours of blind debugging.**

---

## Best Practices — SLIs, SLOs, and Alert Hygiene

### Define SLIs (Service Level Indicators)
| SLI | Measurement |
|---|---|
| Availability | % of successful health checks over 30 days |
| Latency | % of requests served under 500ms |
| Correctness | % of transfers with correct final status |

### Set SLOs (Service Level Objectives)
| SLO | Target | Error Budget |
|---|---|---|
| Availability | 99.9% (three nines) | 43 min downtime/month |
| Latency | 95% of requests < 500ms | 5% can be slower |
| Transfer success | 99.95% | 0.05% allowed failures |

### Avoid Alert Fatigue
| Do | Don't |
|---|---|
| Alert on **symptoms** (high error rate) | Alert on **causes** (single pod restart) |
| Set **meaningful thresholds** | Alert on every minor fluctuation |
| Include **runbook links** in alerts | Send vague "something is wrong" alerts |
| Use **severity levels** (critical vs warning) | Make everything critical |
| **Page for critical**, email for warnings | Page the team for warnings |

**If your team ignores alerts, your alerting is broken — not your team.**

---

## Key Takeaways
- **Observability = Metrics + Logs + Traces** working together
- **Cloud Operations Suite** gives you everything built-in — no extra infrastructure
- **Structured logging** is the single most impactful practice for production debugging
- **Dashboards** should reflect the four golden signals: latency, traffic, errors, saturation
- **Alerts** should be actionable — every alert needs a clear next step
- **SLOs and error budgets** keep reliability conversations objective

### Day 36 Summary
You now have the complete production toolkit:
1. **CI/CD** with Tekton and Cloud Build — automated pipelines
2. **Messaging** with Pub/Sub — event-driven, decoupled services
3. **Monitoring** with Cloud Operations Suite — see everything, fix faster
4. **Alerting** with SLOs — get notified before users complain

**Your UPI service isn't just running — it's observable, automated, and production-ready.**
