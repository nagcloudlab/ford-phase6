# Load Balancing & Traffic Management
## The Gateway to High Availability

---

## What Happens Without a Load Balancer?
```
User → Single Server
```
- Server fails → **entire app goes down**
- Traffic spikes → **server overloaded, users get errors**
- One region → **high latency for distant users**

**A single server is a single point of failure. Load balancers eliminate that.**

---

## What is a Load Balancer?
**Distributes incoming traffic across multiple backend instances**

```
         User Requests
              ↓
        Load Balancer
       /      |       \
  Server 1  Server 2  Server 3
```

### What You Get
- **High availability** — one server dies, others take over
- **Scalability** — add more servers as traffic grows
- **Performance** — distribute load evenly

**Load Balancer = the front door to your system.**

---

## How GCP Load Balancing Works
```
User Request
     ↓
Google Edge Location (nearest to user)
     ↓
Global Load Balancer
     ↓
Backend (Cloud Run / VM / GKE)
```

### The GCP Advantage
- Requests hit the **nearest Google edge** location first
- Routed internally via **Google's private network**
- Not bouncing across the public internet

**Result: Lower latency, higher reliability than traditional load balancers.**

---

## Types of Load Balancers
### By Scope
| Type | Scope | Best For |
|---|---|---|
| **Global LB** | Across all regions | Global apps, multi-region |
| **Regional LB** | Within one region | Local apps, single market |
| **Internal LB** | Private traffic only | Service-to-service calls |

### By Protocol
| Type | Use Case |
|---|---|
| **HTTP/HTTPS** | Web apps, REST APIs (most common) |
| **TCP/UDP** | Low-level protocols, gaming, streaming |

**Default choice: Global HTTP(S) Load Balancer.**

---

## Traffic Distribution Strategies
### How does the LB decide where to send each request?

| Strategy | How It Works | Best For |
|---|---|---|
| **Round Robin** | Equal turns to each server | Uniform workloads |
| **Least Connections** | Send to the least busy server | Variable request times |
| **Geo Routing** | Route based on user location | Global applications |

### Geo Routing Example
```
India users  → Mumbai backend (asia-south1)
US users     → Iowa backend (us-central1)
Europe users → Belgium backend (europe-west1)
```
**Users always hit the closest server. Latency stays low.**

---

## Health Checks: Keeping Your System Alive
**Load balancer continuously checks: "Is this backend healthy?"**

### How It Works
1. LB sends periodic requests to `/health` endpoint
2. Backend responds `200 OK` → **Healthy, keep sending traffic**
3. Backend doesn't respond → **Unhealthy, remove from pool**

### The Flow
```
LB → /health → Server A (200 OK) ✅ → send traffic
LB → /health → Server B (no response) ❌ → stop sending traffic
```

**Without health checks, users get routed to dead servers.**

---

## Failover: Surviving Region Failures
### Scenario: Mumbai region goes completely down

### Without Failover
- App is down for all users
- Manual intervention needed
- Could be hours of downtime

### With Global Load Balancer + Multi-Region
```
Normal:     Users → Mumbai (primary)
Failure:    Mumbai down → LB auto-switches → Delhi (secondary)
Recovery:   Mumbai back → LB gradually returns traffic
```

**Automatic. No human intervention. Users may not even notice.**

---

## Real Production Architecture
```
Users (worldwide)
        ↓
Global Load Balancer
    (HTTPS, SSL termination, geo routing)
        ↓
Cloud Run Services (multi-zone deployment)
    (auto-scaling, private networking)
        ↓
Cloud SQL (Private IP, HA enabled)
    (automated failover, read replicas)
        ↓
Pub/Sub (async event processing)
```

### Architecture Principles
- **Entry point** = Load Balancer (only public component)
- **Backend** = auto-scalable, private, multi-zone
- **Database** = highly available with failover
- **Events** = decoupled via Pub/Sub

---

## Backend Options for Load Balancer
| Backend Type | When to Use |
|---|---|
| **Cloud Run** | Modern microservices (recommended) |
| **VM Instance Groups** | Legacy apps on VMs |
| **GKE** | Kubernetes-based workloads |
| **Cloud Functions** | Lightweight event handlers |

**Cloud Run + Global LB = the sweet spot for most modern apps.**

---

## Real-World Mistakes
| Mistake | What Happens | Fix |
|---|---|---|
| No load balancer | Single point of failure | Always use LB in production |
| No health checks | Traffic sent to dead instances | Configure health check endpoints |
| Single region only | No disaster recovery | Deploy across 2+ regions |
| Wrong routing strategy | High latency for some users | Use geo routing for global apps |
| No SSL/TLS | Data transmitted in plain text | Enable HTTPS on LB |

---

## Expert Takeaways
- **Load Balancer = the gateway** to every production system
- **Global LB** should be your default for modern apps
- **Health checks are mandatory** — never skip them
- **Multi-region + failover** = true enterprise resilience
- **Traffic routing strategy** directly impacts user experience
- This section **completes your core GCP architecture foundation**

---

## Congratulations! Core GCP Foundation Complete
You now understand the full stack:
1. Cloud concepts & service models
2. Projects, IAM & billing
3. Global infrastructure (regions & zones)
4. Service selection framework
5. Compute (VMs & Cloud Run)
6. Storage & databases
7. Networking & security
8. Load balancing & traffic management

**You're ready for the next phase: CI/CD, DevOps & production deployment pipelines.**
