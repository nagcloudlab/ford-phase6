# Cloud Run: The Modern Way to Deploy
## Zero Infra. Auto-Scaling. Pay Per Request.

---

## The Problem Cloud Run Solves
**With VMs, you're still managing servers.**
- Patching OS, configuring networking, manual scaling
- Paying even when nobody is using your app
- Spending time on infra instead of features

**Cloud Run flips the script:**
You give it a Docker container. It handles EVERYTHING else.

---

## Cloud Run in One Sentence
**"Run containers without managing servers."**

### You Provide:
- A Docker image (your app)

### Google Handles:
- Infrastructure
- Auto-scaling
- Load balancing
- Networking
- SSL/HTTPS

**Just deploy and forget infra.**

---

## How It Works Under the Hood
```
User Request
     ↓
Google's Global Load Balancer
     ↓
Cloud Run Service
     ↓
Container Instance (auto-created on demand)
```

### The Magic
| Traffic | Instances | Cost |
|---|---|---|
| 0 users | 0 instances | **₹0** |
| 10 users | 1 instance | Minimal |
| 10,000 users | 20 instances | Scales automatically |
| Back to 0 | 0 instances | **₹0 again** |

**You only pay when someone is actually using your app.**

---

## Key Concepts
### Service
Your deployed application (e.g., `payment-service`, `order-service`)

### Revision
Every deployment creates a new version — easy rollbacks

### Container
Your Docker image — the actual running app

### Concurrency
Requests handled per container instance
- Default: 80 concurrent requests per instance
- Higher concurrency → fewer instances → lower cost

---

## Cloud Run vs VM vs GKE — The Real Comparison
| Feature | VM | Cloud Run | GKE |
|---|---|---|---|
| Infra management | High (you do it all) | None | Medium |
| Scaling | Manual | Automatic | Configurable |
| Complexity | Medium | Very Low | High |
| Cost model | Always on | Per request | Cluster always on |
| Best for | Legacy apps | Microservices | Large-scale systems |

**Cloud Run is the default choice unless you have a specific reason for VM or GKE.**

---

## Deploy Spring Boot to Cloud Run

### Step 1: Create Dockerfile
```dockerfile
FROM openjdk:17-jdk-slim
COPY target/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Step 2: Build & Push Image
```bash
docker build -t gcr.io/PROJECT_ID/myapp .
gcloud auth configure-docker
docker push gcr.io/PROJECT_ID/myapp
```

### Step 3: Deploy
```bash
gcloud run deploy myapp \
  --image gcr.io/PROJECT_ID/myapp \
  --platform managed \
  --region asia-south1 \
  --allow-unauthenticated
```

### Step 4: Access Your App
```
https://myapp-abc123.a.run.app
```
**That's it. No servers. No config. Just a URL.**

---

## Auto-Scaling Deep Dive
### How Scaling Decisions Happen
1. Cloud Run monitors incoming request rate
2. Compares against concurrency setting (default: 80)
3. Spins up new instances when needed
4. Scales down to zero when traffic stops

### The Cold Start Problem
**What:** First request has extra delay while a new instance starts
**Impact:** ~1-5 seconds for Java/Spring Boot apps

### Solutions
- Set `min-instances=1` (keeps one instance warm)
- Optimize app startup time
- Use GraalVM native images for faster boot

---

## Security Options
| Mode | Who Can Access? | Use Case |
|---|---|---|
| Allow unauthenticated | Anyone with the URL | Public APIs, websites |
| Require authentication | Only IAM-authorized users/services | Internal microservices |
| API Gateway | Controlled access with API keys | External partner APIs |

**Default recommendation:** Start authenticated, open up only what's needed.

---

## When to Use Cloud Run (and When Not To)
### Perfect For
- Spring Boot microservices
- REST APIs & GraphQL endpoints
- Event-driven apps (triggered by Pub/Sub)
- Backend services for web/mobile apps

### Avoid When
- Long-running background jobs (>60 min)
- Need full OS-level control
- Stateful applications requiring persistent local storage
- WebSocket connections (limited support)

---

## Real-World Mistakes
| Mistake | Impact | Fix |
|---|---|---|
| Not containerizing properly | App won't start on Cloud Run | Test Docker image locally first |
| Ignoring cold start | Slow first requests | Set min-instances=1 for prod |
| Hardcoding config values | Can't change without redeploy | Use environment variables |
| Jumping to GKE too early | Unnecessary complexity & cost | Start with Cloud Run, migrate if needed |
| No health check endpoint | Can't monitor service health | Add `/health` endpoint |

---

## Expert Takeaways
- **Cloud Run = default compute choice** for modern applications
- **Zero infra management** → focus 100% on business logic
- **Auto-scaling** from 0 to thousands = biggest advantage
- **Containerization is a mandatory skill** — learn Docker well
- **Cost-optimized by design** — pay nothing when idle

---

## What's Next?
**Next:** Cloud Storage — object storage for files, images, backups. How to store and serve assets at scale.
