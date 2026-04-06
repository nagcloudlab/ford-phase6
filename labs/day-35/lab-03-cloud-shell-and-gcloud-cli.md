# Lab 03: Cloud Shell & gcloud CLI

**Duration:** 30 minutes
**Objective:** Master Cloud Shell — your free dev environment in the browser — and learn essential gcloud commands.

---

## Prerequisites

- Lab 02 completed (project created, APIs enabled)

---

## Part 1: Launch Cloud Shell

1. Click the **Cloud Shell** icon (top-right toolbar, looks like a terminal `>_`)
2. Wait ~15 seconds for provisioning
3. A terminal appears at the bottom of the screen

### What you just got — for free

| Feature | Details |
|---------|---------|
| **OS** | Debian Linux |
| **CPU/RAM** | Small VM (good enough for dev) |
| **Storage** | 5 GB persistent home directory |
| **Pre-installed** | `gcloud`, `docker`, `git`, `java 17`, `mvn`, `kubectl`, `python3` |
| **Auth** | Auto-authenticated with your Google account |
| **Cost** | Free — no billing |

### Verify pre-installed tools

```bash
java -version
mvn -version
docker --version
git --version
gcloud version
```

---

## Part 2: Configure Your Project

```bash
# Check current project
gcloud config get-value project

# If it shows the wrong project, set it
gcloud config set project upi-workshop

# Verify
gcloud config get-value project
```

### Set default region and zone

```bash
gcloud config set compute/region asia-south1
gcloud config set compute/zone asia-south1-a

# View full config
gcloud config list
```

### Why defaults matter

Without defaults, every command needs `--region` and `--zone` flags:

```bash
# Without defaults (painful)
gcloud compute instances create my-vm --region=asia-south1 --zone=asia-south1-a

# With defaults (clean)
gcloud compute instances create my-vm
```

---

## Part 3: Essential gcloud Commands

### Cheat Sheet — Run Each One

```bash
# === ACCOUNT & PROJECT ===
gcloud auth list                          # Who am I?
gcloud projects list                      # What projects do I have?
gcloud config get-value project           # Which project am I using?

# === REGIONS & ZONES ===
gcloud compute regions list               # All available regions
gcloud compute zones list --filter="region:asia-south1"   # Zones in Mumbai

# === SERVICES / APIs ===
gcloud services list --enabled            # What APIs are enabled?

# === HELP ===
gcloud help                               # General help
gcloud run deploy --help                  # Help for a specific command
```

### The gcloud pattern

Every gcloud command follows this pattern:

```
gcloud  <service>  <action>  <resource>  [flags]
```

Examples:

```
gcloud  compute    instances  create      my-vm  --zone=asia-south1-a
gcloud  run        deploy     my-service  --image=gcr.io/project/app
gcloud  artifacts  repositories  list     --location=asia-south1
```

---

## Part 4: Upload the UPI Transfer Service

### Option A: Clone from Git (if you have a repo)

```bash
cd ~
git clone <your-repo-url> upi-transfer-service
```

### Option B: Upload from your laptop

1. In Cloud Shell, click the **three-dot menu** (top-right of terminal)
2. Select **Upload**
3. Upload the `upi-transfer-service` folder as a zip
4. Unzip it:

```bash
cd ~
unzip upi-transfer-service.zip
```

### Option C: Trainer provides the code

Your trainer will share the project. Download and upload it using Option B.

### Verify project structure

```bash
cd ~/upi-transfer-service
ls -la
```

You should see:

```
Dockerfile  mvnw  mvnw.cmd  pom.xml  src/
```

---

## Part 5: Build & Run the App in Cloud Shell

```bash
cd ~/upi-transfer-service

# Make the Maven wrapper executable
chmod +x mvnw

# Build the project
./mvnw clean package -DskipTests
```

> First build takes 2-3 minutes (downloading dependencies). Subsequent builds are faster.

### Run the app

```bash
java -jar target/*.jar
```

You'll see Spring Boot start up:

```
Started UpiTransferServiceApplication in 3.2 seconds
```

---

## Part 6: Test the Running App

Open a **second Cloud Shell tab** (click **+** at the top of the terminal).

```bash
# Health check
curl http://localhost:8080/actuator/health
```

```json
{"status":"UP"}
```

```bash
# Check balance
curl -s http://localhost:8080/api/balance/nag@upi | python3 -m json.tool
```

```json
{
    "upiId": "nag@upi",
    "holderName": "Nagendra",
    "balance": 10000.00,
    "servedBy": {
        "hostName": "cs-xxxx",
        "hostAddress": "10.x.x.x"
    }
}
```

```bash
# Make a transfer
curl -s -X POST http://localhost:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "ram@upi",
    "amount": 500,
    "remark": "cloud shell test"
  }' | python3 -m json.tool
```

```bash
# View transactions
curl -s http://localhost:8080/api/transactions/nag@upi | python3 -m json.tool
```

### Try the Swagger UI

1. In Cloud Shell toolbar, click **Web Preview > Preview on port 8080**
2. A browser tab opens — append `/swagger-ui.html` to the URL
3. You can test all APIs interactively from the Swagger UI

### Stop the app

Go back to the first tab and press **Ctrl+C**.

---

## Checkpoint

- [ ] Cloud Shell launched and configured with project + region
- [ ] Know the gcloud command pattern: `gcloud <service> <action> <resource>`
- [ ] UPI Transfer Service uploaded to Cloud Shell
- [ ] App built and running locally
- [ ] Tested all 3 API endpoints with `curl`
- [ ] Tried Swagger UI via Web Preview

---

## Key Takeaways

- **Cloud Shell = free dev environment** — no laptop setup needed, everything pre-installed
- **`gcloud config set` saves defaults** — less typing, fewer mistakes
- **Every gcloud command follows the same pattern** — learn the pattern, not individual commands
- **The app runs the same everywhere** — Cloud Shell, your laptop, a VM, a container. Same Java, same jar.
