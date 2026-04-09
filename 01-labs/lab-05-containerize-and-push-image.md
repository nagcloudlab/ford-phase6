# Lab 05: Containerize & Push to Artifact Registry

**Duration:** 35 minutes
**Objective:** Build a Docker image of the UPI Transfer Service and push it to Google Artifact Registry — making it deployable anywhere.

---

## Prerequisites

- Lab 04 completed (you've experienced the VM pain)

---

## The Shift: From Jar to Container

In the VM lab, you had to:
1. Install Java on the server
2. Copy the jar manually
3. Run it manually
4. Manage everything yourself

With containers, you package **everything** (Java + app + config) into **one image** that runs identically everywhere.

```
VM Way:     Server + install Java + copy jar + run manually
Container:  One image = Java + app + everything → runs anywhere
```

---

## Part 1: Understand the Dockerfile

```bash
cd ~/upi-transfer-service
cat Dockerfile
```

```dockerfile
# STAGE 1: Build
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN chmod +x mvnw && ./mvnw package -DskipTests

# STAGE 2: Run
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Breaking it down

| Stage | What Happens | Contains |
|-------|-------------|----------|
| **Build stage** | Downloads dependencies, compiles code, creates jar | JDK + Maven + source code + jar |
| **Run stage** | Takes ONLY the jar from build stage | JRE + jar (nothing else) |

### Why two stages?

| Approach | Image Size | Security |
|----------|-----------|----------|
| Single stage | ~400 MB (includes JDK, Maven, source) | Larger attack surface |
| Multi-stage | ~180 MB (JRE + jar only) | Minimal attack surface |

**Build tools never reach production. Only the jar does.**

---

## Part 2: Build the Docker Image

```bash
cd ~/upi-transfer-service

# Build the image
docker build -t upi-transfer-service:v1 .
```

> First build: 2-3 minutes (downloading base images + dependencies).
> Subsequent builds: Much faster (Docker layer caching).

### Verify the image was created

```bash
docker images | grep upi
```

```
upi-transfer-service   v1    abc123   2 minutes ago   182MB
```

---

## Part 3: Run the Container Locally

```bash
# Run in detached mode
docker run -d --name upi-app -p 8080:8080 upi-transfer-service:v1

# Check it's running
docker ps
```

### Test it

```bash
# Health check
curl -s http://localhost:8080/actuator/health

# Balance
curl -s http://localhost:8080/api/balance/nag@upi | python3 -m json.tool

# Transfer
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "ram@upi",
    "amount": 300,
    "remark": "container test"
  }' | python3 -m json.tool
```

### View logs

```bash
docker logs upi-app
```

### Compare: VM vs Container

| Aspect | VM (Lab 04) | Container (Now) |
|--------|-------------|----------------|
| Setup time | ~10 min (create VM, SSH, install Java, copy jar) | ~3 min (docker build + run) |
| Java installed by you? | Yes, manually | No, baked into image |
| Firewall config? | Yes, manually | No (mapped port with -p) |
| Reproducible? | No (depends on server state) | Yes (same image = same behavior) |

### Cleanup

```bash
docker stop upi-app
docker rm upi-app
```

---

## Part 4: Create Artifact Registry Repository

**Artifact Registry** = Google's managed Docker image registry. This is where your production images live.

```bash
# Create a Docker repository
gcloud artifacts repositories create upi-repo \
  --repository-format=docker \
  --location=asia-south1 \
  --description="UPI Transfer Service Docker images"
```

### Verify

```bash
gcloud artifacts repositories list --location=asia-south1
```

### Configure Docker to authenticate with Artifact Registry

```bash
gcloud auth configure-docker asia-south1-docker.pkg.dev
```

> This adds Artifact Registry as a trusted registry so `docker push` works.

---

## Part 5: Tag & Push the Image

Docker images need to be **tagged** with the registry URL before pushing.

```bash
# Get your project ID
export PROJECT_ID=$(gcloud config get-value project)
echo "Project ID: $PROJECT_ID"

# Tag the image
docker tag upi-transfer-service:v1 \
  asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:v1

# Push to Artifact Registry
docker push \
  asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:v1

  # or 

  gcloud builds submit \
  --tag asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:v1 \
  .
```

> Push takes 1-2 minutes depending on your connection.

### Verify in Console

1. Go to **Navigation Menu > Artifact Registry**
2. Click **upi-repo**
3. You should see `upi-transfer-service` with tag `v1`

### Verify via CLI

```bash
gcloud artifacts docker images list \
  asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo \
  --format="table(package, tags, createTime)"
```

---

## Part 6: The Big Picture

```
Source Code (Java + pom.xml)
        │
        ▼
    Dockerfile (build instructions)
        │
        ▼
    docker build (creates image locally)
        │
        ▼
    docker tag (names it for the registry)
        │
        ▼
    docker push (uploads to Artifact Registry)
        │
        ▼
    Image in Artifact Registry (ready for deployment)
        │
        ├──▶ Cloud Run (next lab!)
        ├──▶ GKE (Kubernetes)
        └──▶ Any VM / any cloud
```

**The same image that ran on Cloud Shell runs identically on Cloud Run, on a VM, or on Kubernetes.** That's the power of containers.

---

## Checkpoint

- [ ] Understand the multi-stage Dockerfile (build stage vs run stage)
- [ ] Built a Docker image locally (~180 MB)
- [ ] Ran and tested the containerized app
- [ ] Created an Artifact Registry repository (`upi-repo`)
- [ ] Pushed image to Artifact Registry with tag `v1`
- [ ] Verified the image in both Console and CLI

---

## Key Takeaways

- **Container = your app + everything it needs** — no "works on my machine" problems
- **Multi-stage builds = smaller, more secure images** — build tools never reach production
- **Artifact Registry = your image warehouse** — versioned, secure, accessible from any GCP service
- **Build once, deploy anywhere** — the same image runs on Cloud Run, GKE, or any VM
- **Next:** We deploy this image to Cloud Run — one command, live HTTPS URL, auto-scaling
