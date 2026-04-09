# Core GCP Services Overview
## Choosing the Right Tool for Every Job

---

## The Architect's Dilemma
You need to build a system. GCP has 200+ services.
**The real skill isn't knowing every service — it's knowing WHICH one to pick and WHEN.**

This section gives you the decision framework that separates beginners from architects.

---

## The 5 Service Categories
| Category | Purpose | Key Question |
|---|---|---|
| **Compute** | Run your application | Where should my app live? |
| **Storage** | Store files & assets | Where do my files go? |
| **Databases** | Store structured data | Where does my data live? |
| **Networking** | Connect everything | How do services talk? |
| **Messaging** | Async communication | How do I decouple services? |

---

## Compute Services: Where Your App Runs

### Compute Engine (VMs)
- Full control over OS, runtime, configs
- **Best for:** Legacy apps, custom setups, learning infra
- **Avoid when:** You don't want to manage servers

### App Engine (PaaS)
- Deploy code, no server management
- **Best for:** Simple web apps, prototypes
- **Limitation:** Less flexibility

### Cloud Run (Serverless Containers)
- Run Docker containers with zero infra management
- **Best for:** Microservices, REST APIs, modern apps
- **Superpower:** Auto-scaling, pay-per-request

### GKE (Kubernetes Engine)
- Full Kubernetes orchestration
- **Best for:** Large-scale microservices, complex systems
- **Avoid when:** Small apps (massive overkill)

---

## The Compute Decision Table
| Your Situation | Choose This |
|---|---|
| Need full OS control | **Compute Engine** (VM) |
| Simple web app, no infra worry | **App Engine** |
| Microservices & REST APIs | **Cloud Run** |
| Complex orchestration at scale | **GKE** |

**Default modern choice: Cloud Run** — unless you have a specific reason for something else.

---

## Storage: Where Your Files Live

### Cloud Storage (Object Storage)
Store images, videos, PDFs, backups — any file.

| Storage Class | Use Case | Cost |
|---|---|---|
| Standard | Frequently accessed files | Highest |
| Nearline | Monthly access (reports) | Medium |
| Coldline | Rare access (compliance) | Low |
| Archive | Almost never accessed | Lowest |

**Example mapping:**
- User profile images → Standard
- Monthly backups → Coldline
- 7-year audit logs → Archive

---

## Databases: Where Your Data Lives

### Cloud SQL
- Managed MySQL / PostgreSQL
- **Best for:** Transactions, structured data, banking systems

### Firestore
- NoSQL document database
- **Best for:** Real-time apps, flexible schema, mobile backends

### BigQuery
- Analytics data warehouse
- **Best for:** Large-scale analytics, reporting, dashboards

### The DB Decision Cheat Sheet
| Need | Service |
|---|---|
| ACID transactions (payments, orders) | **Cloud SQL** |
| Flexible, real-time data | **Firestore** |
| Analytics on massive datasets | **BigQuery** |

---

## Networking: The Backbone

| Service | Purpose |
|---|---|
| **VPC** | Your private network in cloud |
| **Load Balancer** | Distributes traffic across instances |
| **Firewall** | Controls who can access what |

**Golden rule:** Public entry point (Load Balancer) → Private everything else.

---

## Messaging: Decoupling Services

### Pub/Sub — The Event Bus
- Asynchronous communication between services
- **Example:** `Order Service → Pub/Sub → Payment Service`
- Enables event-driven architecture
- Handles traffic spikes gracefully

**When to use:** Anytime services shouldn't wait for each other.

---

## Putting It All Together: Real Architecture
### Example: UPI-like Payment System

```
User Request
     ↓
Cloud Load Balancer (traffic entry)
     ↓
Cloud Run (API — auto-scaling)
     ↓
Cloud SQL (transactions — ACID)
     ↓
Pub/Sub (async events)
     ↓
BigQuery (analytics & reporting)
```

**Each service has ONE clear responsibility.**

---

## Common Mistakes to Avoid
| Mistake | Why It's Wrong | Fix |
|---|---|---|
| Using VMs for everything | Old-school mindset | Use Cloud Run for microservices |
| Kubernetes too early | Overengineering | Start with Cloud Run, graduate to GKE |
| Storing files in database | Wrong tool, bad performance | Use Cloud Storage for files |
| Ignoring async messaging | Tight coupling, fragile system | Use Pub/Sub to decouple |

---

## Expert Takeaways
- **Compute choice** = the biggest architectural decision you'll make
- **Cloud Run** = your default for modern apps
- **Storage ≠ Database** — keep this separation clear
- **Pub/Sub** enables resilient, event-driven systems
- **Think like an architect:** choose services by need, not by popularity

---

## What's Next?
**Next:** Deep dive into Compute Engine (VMs) — hands-on with real infrastructure.
