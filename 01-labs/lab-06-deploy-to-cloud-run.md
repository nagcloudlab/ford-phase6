# Lab 06: Deploy to Cloud Run

**Duration:** 30 minutes
**Objective:** Deploy the UPI Transfer Service to Cloud Run with one command. Get a live HTTPS URL. Compare the experience with the VM deployment in Lab 04.

---

## Prerequisites

- Lab 05 completed (image pushed to Artifact Registry)

---

## The Moment of Truth

Remember Lab 04? Creating a VM, SSHing, installing Java, copying the jar, opening the firewall, running manually...

**Watch what Cloud Run does with one command.**

---

## Part 1: Deploy

```bash
export PROJECT_ID=$(gcloud config get-value project)

gcloud run deploy upi-transfer-service \
  --image asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:v1 \
  --platform managed \
  --region asia-south1 \
  --allow-unauthenticated \
  --port 8080 \
  --memory 512Mi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 5
```

### What each flag does

| Flag | Purpose |
|------|---------|
| `--image` | The container image from Artifact Registry |
| `--platform managed` | Fully managed by Google (not self-hosted) |
| `--region asia-south1` | Deploy in Mumbai |
| `--allow-unauthenticated` | Public access (anyone can call the API) |
| `--port 8080` | Container listens on port 8080 |
| `--memory 512Mi` | RAM per instance |
| `--min-instances 0` | Scale to zero when idle |
| `--max-instances 5` | Cap at 5 instances during spikes |

### Output

```
Deploying container to Cloud Run service [upi-transfer-service] in project [upi-workshop] region [asia-south1]
✓ Deploying... Done.
  ✓ Creating Revision...
  ✓ Routing traffic...
Done.
Service [upi-transfer-service] revision [upi-transfer-service-00001-xxx] has been deployed
and is serving 100% of traffic.

Service URL: https://upi-transfer-service-xxxxx-el.a.run.app
```

### Save the URL

```bash
export SERVICE_URL=$(gcloud run services describe upi-transfer-service \
  --region asia-south1 --format='value(status.url)')
echo "Live URL: $SERVICE_URL"
```

---

## Part 2: Test the Live Service

```bash
# Health check
curl -s $SERVICE_URL/actuator/health | python3 -m json.tool

# Balance
curl -s $SERVICE_URL/api/balance/nag@upi | python3 -m json.tool

# Transfer
curl -s -X POST $SERVICE_URL/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "priya@upi",
    "amount": 250,
    "remark": "cloud run live!"
  }' | python3 -m json.tool

# Transactions
curl -s $SERVICE_URL/api/transactions/nag@upi | python3 -m json.tool
```

### Open Swagger UI in Browser

```bash
echo "$SERVICE_URL/swagger-ui.html"
```

Copy the URL and open it in your browser. You have a **fully documented, live API** on the internet.

---

## Part 3: VM vs Cloud Run — The Side-by-Side

| What | VM (Lab 04) | Cloud Run (Now) |
|------|-------------|----------------|
| **Commands to deploy** | ~8 (create VM, SSH, install Java, copy jar, firewall, run) | **1** |
| **Time to deploy** | ~15 minutes | **~30 seconds** |
| **HTTPS** | Not included (manual SSL setup) | **Free, automatic** |
| **Custom domain** | Manual DNS + cert | One command |
| **Auto-scaling** | No (manual VM cloning) | **Yes (0 to N instances)** |
| **Cost when idle** | **Paying 24/7** | **$0 (scale to zero)** |
| **Server crashes** | App is down, manual restart | **Auto-restart** |
| **OS patching** | Your job | **Google handles it** |
| **Firewall setup** | Manual | **Not needed** |

**One command replaced 45 minutes of manual work.**

---

## Part 4: Explore in Console

1. Go to **Navigation Menu > Cloud Run**
2. Click **upi-transfer-service**

### Explore each tab

| Tab | What You Learn |
|-----|---------------|
| **Metrics** | Request count, latency (p50/p95/p99), instance count, CPU/memory |
| **Logs** | Spring Boot startup logs, request logs, errors |
| **Revisions** | Every deployment is versioned — click to see config of each |
| **Networking** | Ingress settings, custom domains, authentication mode |
| **Security** | Service account, authentication settings |
| **YAML** | The complete service specification |

---

## Part 5: Observe the servedBy Field

```bash
curl -s $SERVICE_URL/api/balance/ram@upi | python3 -m json.tool
```

```json
{
    "upiId": "ram@upi",
    "holderName": "Ram Kumar",
    "balance": 5300.00,
    "servedBy": {
        "hostName": "localhost",
        "hostAddress": "169.254.8.129"
    }
}
```

The `servedBy` field tells you **which container instance** handled the request. Right now there's likely just one instance. In Lab 07, when we trigger scaling, you'll see different hostnames — proving that Cloud Run is distributing requests across multiple instances.

---

## Part 6: Deploy a New Version (Revision)

Let's make a change and deploy v2 to see how revisions work.

### Edit data.sql — add a new account

In Cloud Shell Editor, open `~/upi-transfer-service/src/main/resources/data.sql` and add:

```sql
INSERT INTO accounts (upi_id, holder_name, balance) VALUES ('test@upi', 'Test User', 3000.00);
```

### Build, tag, push, deploy

```bash
cd ~/upi-transfer-service

# Build v2
docker build -t upi-transfer-service:v2 .

# Tag
docker tag upi-transfer-service:v2 \
  asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:v2

# Push
docker push \
  asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:v2

# Deploy v2
gcloud run deploy upi-transfer-service \
  --image asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:v2 \
  --region asia-south1
```

### Verify the new account

```bash
curl -s $SERVICE_URL/api/balance/test@upi | python3 -m json.tool
```

### See revisions in Console

1. Go to **Cloud Run > upi-transfer-service > Revisions**
2. You see both **revision 1** and **revision 2**
3. Revision 2 is serving 100% of traffic

---

## Checkpoint

- [ ] Deployed to Cloud Run with one command
- [ ] Got a live HTTPS URL
- [ ] Tested all APIs on the live service
- [ ] Opened Swagger UI in the browser
- [ ] Compared VM deployment (Lab 04) with Cloud Run (night and day)
- [ ] Explored Metrics, Logs, and Revisions in the Console
- [ ] Deployed a second revision (v2)

---

## Key Takeaways

- **One command: image to live HTTPS URL** — that's the Cloud Run promise
- **HTTPS is free and automatic** — Google provisions and renews SSL certificates
- **Revisions = built-in version control** — every deploy is tracked, rollback is instant
- **Scale to zero = pay nothing when idle** — fundamentally different cost model from VMs
- **The VM experience exists to make you appreciate this moment** — same app, radically different experience
