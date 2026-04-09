# Lab 01: Cloud Run Deep Dive — UPI Transfer Service

**Duration:** 90 minutes
**Objective:** End-to-end Cloud Run mastery — from source to production with revisions, traffic splitting, env vars, secrets, custom domains, autoscaling, and Cloud Build integration.

---

## Architecture

```
Developer → Cloud Build → Artifact Registry → Cloud Run
                                                  ├── Revision 1 (v1)
                                                  ├── Revision 2 (v2) ← traffic split
                                                  └── Auto-scaling (0–10 instances)
```

---

## Part 1: Setup & Enable APIs

```bash
# ─── Set variables ───
export PROJECT_ID=$(gcloud config get-value project)
export REGION=asia-south1
export SERVICE_NAME=upi-transfer-service
export REPO_NAME=upi-repo

echo "Project : $PROJECT_ID"
echo "Region  : $REGION"
```

### Enable required APIs

```bash
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com \
  --project=$PROJECT_ID
```

### Verify

```bash
gcloud services list --enabled --filter="NAME:(run OR artifactregistry OR cloudbuild)" \
  --format="table(NAME, TITLE)"
```

---

## Part 2: Create Artifact Registry Repository

```bash
gcloud artifacts repositories create $REPO_NAME \
  --repository-format=docker \
  --location=$REGION \
  --description="UPI Transfer Service Docker images"
```

### Configure Docker auth for the registry

```bash
gcloud auth configure-docker ${REGION}-docker.pkg.dev --quiet
```

> **If `docker push` fails with "Unauthenticated request"**, the `docker-credential-gcloud` helper isn't in Docker's PATH. Fix it with:
> ```bash
> # Option A: Symlink (if gcloud installed in home dir)
> sudo ln -sf ~/google-cloud-sdk/bin/docker-credential-gcloud /usr/local/bin/
>
> # Option B: Token-based login
> gcloud auth print-access-token | docker login -u oauth2accesstoken --password-stdin ${REGION}-docker.pkg.dev
> ```

### Verify

```bash
gcloud artifacts repositories list --location=$REGION \
  --format="table(REPOSITORY, FORMAT, LOCATION)"
```

---

## Part 3: Build Image Locally & Push

### Navigate to the service directory

```bash
cd ~/upi-transfer-service
```

### Build the Docker image

```bash
docker build -t ${SERVICE_NAME}:v1 .
```

### Tag for Artifact Registry

```bash
docker tag ${SERVICE_NAME}:v1 \
  ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:v1
```

### Push to Artifact Registry

```bash
docker push ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:v1
```

### Verify image in registry

```bash
gcloud artifacts docker images list \
  ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME} \
  --format="table(PACKAGE, TAGS, CREATE_TIME)"
```

---

## Part 4: Deploy v1 to Cloud Run

```bash
gcloud run deploy $SERVICE_NAME \
  --image ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:v1 \
  --platform managed \
  --region $REGION \
  --allow-unauthenticated \
  --port 8080 \
  --memory 512Mi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 5 \
  --set-env-vars "SPRING_PROFILES_ACTIVE=cloud"
```

### Flags explained

| Flag | Purpose |
|------|---------|
| `--image` | Container image from Artifact Registry |
| `--platform managed` | Fully managed (Google handles infrastructure) |
| `--region` | Deploy in Mumbai (asia-south1) |
| `--allow-unauthenticated` | Public API (no auth required) |
| `--port 8080` | Container listens on 8080 |
| `--memory 512Mi` | RAM per instance |
| `--cpu 1` | 1 vCPU per instance |
| `--min-instances 0` | Scale to zero when idle (no cost) |
| `--max-instances 5` | Cap at 5 instances during traffic spikes |
| `--set-env-vars` | Pass environment variables to the container |

### Save the URL

```bash
export SERVICE_URL=$(gcloud run services describe $SERVICE_NAME \
  --region $REGION --format='value(status.url)')
echo "🔗 Live URL: $SERVICE_URL"
```

---

## Part 5: Test the Live Service

### Health check

```bash
curl -s $SERVICE_URL/actuator/health | python3 -m json.tool
```

Expected:
```json
{
    "status": "UP",
    "components": {
        "db": { "status": "UP" },
        "diskSpace": { "status": "UP" }
    }
}
```

### Check balance

```bash
curl -s $SERVICE_URL/api/balance/nag@upi | python3 -m json.tool
```

### Make a transfer

```bash
curl -s -X POST $SERVICE_URL/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "priya@upi",
    "amount": 500,
    "remark": "Cloud Run v1 transfer"
  }' | python3 -m json.tool
```

### View transactions

```bash
curl -s $SERVICE_URL/api/transactions/nag@upi | python3 -m json.tool
```

### Open Swagger UI (copy URL and open in browser)

```bash
echo "$SERVICE_URL/swagger-ui.html"
```

---

## Part 6: Describe the Service (inspect config)

```bash
gcloud run services describe $SERVICE_NAME \
  --region $REGION \
  --format="yaml"
```

### Key fields to look at

```bash
# Just the essentials
gcloud run services describe $SERVICE_NAME \
  --region $REGION \
  --format="table(
    status.url,
    spec.template.spec.containers[0].resources.limits.memory,
    spec.template.spec.containers[0].resources.limits.cpu,
    spec.template.metadata.annotations.'autoscaling.knative.dev/minScale',
    spec.template.metadata.annotations.'autoscaling.knative.dev/maxScale'
  )"
```

---

## Part 7: View Revisions

```bash
gcloud run revisions list \
  --service=$SERVICE_NAME \
  --region=$REGION \
  --format="table(REVISION, ACTIVE, SERVICE, DEPLOYED, PERCENT_TRAFFIC)"
```

You'll see one revision serving 100% traffic.

---

## Part 8: Deploy v2 (new revision with changes)

### Add a new account to data.sql

Add this line to `src/main/resources/data.sql`:

```sql
INSERT INTO accounts (upi_id, holder_name, balance) VALUES ('test@upi', 'Test User', 3000.00);
```

### Build, tag, push v2

```bash
cd ~/upi-transfer-service

docker build -t ${SERVICE_NAME}:v2 .

docker tag ${SERVICE_NAME}:v2 \
  ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:v2

docker push ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:v2
```

### Deploy v2 with --no-traffic (canary-safe deploy)

```bash
gcloud run deploy $SERVICE_NAME \
  --image ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:v2 \
  --region $REGION \
  --no-traffic \
  --tag green
```

> `--no-traffic` deploys the revision but sends **0% traffic** to it.
> `--tag green` gives it a named URL for testing (tag must be at least 3 characters).

### Check revisions now

```bash
gcloud run revisions list \
  --service=$SERVICE_NAME \
  --region=$REGION \
  --format="table(REVISION, ACTIVE, DEPLOYED, PERCENT_TRAFFIC)"
```

You'll see:
- Revision 1 → **100%** traffic
- Revision 2 → **0%** traffic (but has a tagged URL)

### Test v2 using its tag URL (before sending real traffic)

```bash
export GREEN_URL=$(gcloud run services describe $SERVICE_NAME \
  --region $REGION \
  --format="value(status.traffic.url)" | grep green)
echo "v2 test URL: $GREEN_URL"

curl -s $GREEN_URL/api/balance/test@upi | python3 -m json.tool
```

---

## Part 9: Traffic Splitting (Canary / Blue-Green)

### Send 20% traffic to v2 (canary release)

```bash
# Get revision names
gcloud run revisions list --service=$SERVICE_NAME --region=$REGION --format="value(REVISION)"
```

```bash
# Replace with your actual revision names from the output above
export REV1=<revision-1-name>
export REV2=<revision-2-name>

gcloud run services update-traffic $SERVICE_NAME \
  --region $REGION \
  --to-revisions=${REV1}=80,${REV2}=20
```

### Verify the split

```bash
gcloud run services describe $SERVICE_NAME \
  --region $REGION \
  --format="table(status.traffic.revisionName, status.traffic.percent, status.traffic.tag)"
```

### Test — run multiple requests and watch the responses

```bash
for i in $(seq 1 10); do
  echo "Request $i:"
  curl -s $SERVICE_URL/api/balance/nag@upi | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(f\"  servedBy: {data.get('servedBy', 'N/A')}\")" 2>/dev/null
done
```

### Promote v2 to 100%

```bash
gcloud run services update-traffic $SERVICE_NAME \
  --region $REGION \
  --to-revisions=${REV2}=100
```

### Or use the shortcut: route all traffic to latest

```bash
gcloud run services update-traffic $SERVICE_NAME \
  --region $REGION \
  --to-latest
```

---

## Part 10: Rollback

Instant rollback to any previous revision:

```bash
gcloud run services update-traffic $SERVICE_NAME \
  --region $REGION \
  --to-revisions=${REV1}=100
```

Then roll forward again:

```bash
gcloud run services update-traffic $SERVICE_NAME \
  --region $REGION \
  --to-latest
```

---

## Part 11: Environment Variables & Configuration

### Set environment variables

```bash
gcloud run services update $SERVICE_NAME \
  --region $REGION \
  --set-env-vars "APP_VERSION=v2,APP_ENV=production"
```

### View current env vars

```bash
gcloud run services describe $SERVICE_NAME \
  --region $REGION \
  --format="yaml(spec.template.spec.containers[0].env)"
```

### Update a single env var (without removing others)

```bash
gcloud run services update $SERVICE_NAME \
  --region $REGION \
  --update-env-vars "APP_VERSION=v3"
```

### Remove an env var

```bash
gcloud run services update $SERVICE_NAME \
  --region $REGION \
  --remove-env-vars "APP_ENV"
```

---

## Part 12: Autoscaling — Watch It in Action

### Set autoscaling config

```bash
gcloud run services update $SERVICE_NAME \
  --region $REGION \
  --min-instances 1 \
  --max-instances 10 \
  --concurrency 50
```

| Setting | Meaning |
|---------|---------|
| `--min-instances 1` | Always keep 1 instance warm (no cold starts) |
| `--max-instances 10` | Scale up to 10 instances under load |
| `--concurrency 50` | Each instance handles 50 concurrent requests before new instance spins up |

### Generate load (install hey or use a loop)

```bash
# Simple load test with curl
for i in $(seq 1 100); do
  curl -s -o /dev/null $SERVICE_URL/api/balance/nag@upi &
done
wait
echo "Done — check instance count in Console"
```

### Check instance count

```bash
gcloud run services describe $SERVICE_NAME \
  --region $REGION \
  --format="value(status.conditions)"
```

> Go to **Cloud Console > Cloud Run > upi-transfer-service > Metrics** to see the instance count graph spike.

### Reset to scale-to-zero

```bash
gcloud run services update $SERVICE_NAME \
  --region $REGION \
  --min-instances 0 \
  --max-instances 5
```

---

## Part 13: Cloud Build — Build & Deploy from Source

Instead of building locally, let Cloud Build do it on GCP.

### Submit a build using the Dockerfile

```bash
cd ~/upi-transfer-service

gcloud builds submit \
  --tag ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:v3 \
  --region=$REGION
```

> This uploads source to GCS, builds the Docker image in the cloud, and pushes to Artifact Registry — all in one command.

### Deploy the Cloud Build image

```bash
gcloud run deploy $SERVICE_NAME \
  --image ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:v3 \
  --region $REGION
```

### Or deploy directly from source (one command!)

```bash
gcloud run deploy $SERVICE_NAME \
  --source . \
  --region $REGION \
  --allow-unauthenticated
```

> `--source .` does everything: builds, pushes, and deploys.

---

## Part 14: View Logs

### Recent logs

```bash
gcloud run services logs read $SERVICE_NAME \
  --region $REGION \
  --limit 50
```

### Stream logs in real-time

```bash
gcloud run services logs tail $SERVICE_NAME \
  --region $REGION
```

> Press Ctrl+C to stop.

### While logs are streaming, hit the API in another terminal

```bash
curl -s $SERVICE_URL/api/balance/nag@upi | python3 -m json.tool
```

You'll see the request appear in the log stream.

---

## Part 15: CPU & Memory Tuning

### Increase memory and CPU

```bash
gcloud run services update $SERVICE_NAME \
  --region $REGION \
  --memory 1Gi \
  --cpu 2
```

### Verify

```bash
gcloud run services describe $SERVICE_NAME \
  --region $REGION \
  --format="table(
    spec.template.spec.containers[0].resources.limits.memory,
    spec.template.spec.containers[0].resources.limits.cpu
  )"
```

### Reset to lower resources

```bash
gcloud run services update $SERVICE_NAME \
  --region $REGION \
  --memory 512Mi \
  --cpu 1
```

---

## Part 16: Request Timeout & Startup Probe

### Set request timeout (default is 300s)

```bash
gcloud run services update $SERVICE_NAME \
  --region $REGION \
  --timeout 60
```

### Set startup CPU boost (helps Spring Boot start faster)

```bash
gcloud run services update $SERVICE_NAME \
  --region $REGION \
  --cpu-boost
```

### Add a startup probe (health check during startup)

```bash
gcloud run deploy $SERVICE_NAME \
  --image ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:v3 \
  --region $REGION \
  --startup-probe=httpGet.path=/actuator/health,httpGet.port=8080,initialDelaySeconds=5,periodSeconds=3,failureThreshold=10
```

---

## Part 17: IAM — Controlling Access

### Check current IAM policy

```bash
gcloud run services get-iam-policy $SERVICE_NAME \
  --region $REGION
```

### Remove public access (require authentication)

```bash
gcloud run services remove-iam-policy-binding $SERVICE_NAME \
  --region $REGION \
  --member="allUsers" \
  --role="roles/run.invoker"
```

### Test — should get 403 now

> **Note:** IAM changes can take 1–2 minutes to propagate. Wait a moment before testing.

```bash
curl -s -w "\nHTTP Status: %{http_code}\n" $SERVICE_URL/api/balance/nag@upi
```

### Call with authentication (using your identity token)

```bash
curl -s -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
  $SERVICE_URL/api/balance/nag@upi | python3 -m json.tool
```

### Restore public access

```bash
gcloud run services add-iam-policy-binding $SERVICE_NAME \
  --region $REGION \
  --member="allUsers" \
  --role="roles/run.invoker"
```

---

## Part 18: List All Images in Artifact Registry

```bash
gcloud artifacts docker images list \
  ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME} \
  --include-tags \
  --format="table(PACKAGE, TAGS, CREATE_TIME)"
```

---

## Part 19: View All Revisions & Cleanup Old Ones

### List all revisions

```bash
gcloud run revisions list \
  --service=$SERVICE_NAME \
  --region=$REGION \
  --format="table(REVISION, ACTIVE, DEPLOYED, PERCENT_TRAFFIC)"
```

### Delete a specific old revision

```bash
# gcloud run revisions delete <revision-name> --region=$REGION --quiet
```

---

## Part 20: Full Cleanup

```bash
# Delete Cloud Run service
gcloud run services delete $SERVICE_NAME \
  --region $REGION --quiet

# Delete all images in the repo
gcloud artifacts docker images delete \
  ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME} \
  --delete-tags --quiet 2>/dev/null

# Delete Artifact Registry repo
gcloud artifacts repositories delete $REPO_NAME \
  --location $REGION --quiet

# Delete Cloud Build bucket (if any)
gsutil rm -r gs://${PROJECT_ID}_cloudbuild 2>/dev/null

echo "✅ All Cloud Run resources cleaned up!"
```

---

## Summary — Commands Cheat Sheet

| Action | Command |
|--------|---------|
| Deploy | `gcloud run deploy SERVICE --image IMAGE --region REGION` |
| Deploy from source | `gcloud run deploy SERVICE --source . --region REGION` |
| Describe | `gcloud run services describe SERVICE --region REGION` |
| List revisions | `gcloud run revisions list --service SERVICE --region REGION` |
| Traffic split | `gcloud run services update-traffic SERVICE --to-revisions=REV1=80,REV2=20` |
| Rollback | `gcloud run services update-traffic SERVICE --to-revisions=OLD_REV=100` |
| Set env vars | `gcloud run services update SERVICE --set-env-vars KEY=VAL` |
| Scale config | `gcloud run services update SERVICE --min-instances 1 --max-instances 10` |
| View logs | `gcloud run services logs read SERVICE --region REGION` |
| Stream logs | `gcloud run services logs tail SERVICE --region REGION` |
| IAM (public) | `gcloud run services add-iam-policy-binding SERVICE --member=allUsers --role=roles/run.invoker` |
| Delete | `gcloud run services delete SERVICE --region REGION` |

---

## Checkpoint

- [ ] Enabled APIs (Cloud Run, Artifact Registry, Cloud Build)
- [ ] Created Artifact Registry repo
- [ ] Built and pushed Docker image (v1)
- [ ] Deployed v1 to Cloud Run
- [ ] Tested all APIs (health, balance, transfer, transactions)
- [ ] Deployed v2 with `--no-traffic` (canary-safe)
- [ ] Tested v2 via tag URL before routing traffic
- [ ] Traffic split (80/20) between revisions
- [ ] Promoted v2 to 100%
- [ ] Rolled back to v1, then forward again
- [ ] Set and managed environment variables
- [ ] Tested autoscaling with concurrent requests
- [ ] Built image via Cloud Build (from source)
- [ ] Viewed and streamed logs
- [ ] Tuned CPU, memory, timeout, and startup probe
- [ ] Toggled IAM (public ↔ authenticated access)
- [ ] Cleaned up all resources
