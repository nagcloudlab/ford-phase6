# Lab 01: CI/CD with Cloud Build

**Duration:** 40 minutes
**Objective:** Set up a Cloud Build pipeline that automatically builds, containerizes, and deploys the UPI Transfer Service to Cloud Run.

---

## Prerequisites

- Day 35 labs completed (UPI service deployed on Cloud Run, image in Artifact Registry)
- Artifact Registry repository `upi-repo` exists in `asia-south1`

---

## Why CI/CD?

In Day 35, every deployment required manual steps:

```
Edit code → mvn package → docker build → docker tag → docker push → gcloud run deploy
```

With Cloud Build, you define this **once** in a YAML file, and every code push triggers it automatically.

| Approach | Steps | Time | Error-prone? |
|----------|-------|------|-------------|
| Manual (Day 35) | 6 commands, copy-paste | ~5 min | Yes — wrong tag, forgot a step |
| Cloud Build | Push code, everything automatic | ~3 min | No — same pipeline every time |

---

## Part 1: Enable Required APIs

```bash
gcloud services enable cloudbuild.googleapis.com
gcloud services enable run.googleapis.com
gcloud services enable artifactregistry.googleapis.com
```

### Grant Cloud Build permission to deploy to Cloud Run

```bash
export PROJECT_ID=$(gcloud config get-value project)
export PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')

# Grant Cloud Run Admin role to Cloud Build service account
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com" \
  --role="roles/run.admin"

# Grant Service Account User role (needed to act as the runtime service account)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com" \
  --role="roles/iam.serviceAccountUser"
```

> Without these permissions, Cloud Build can build your image but cannot deploy it.

---

## Part 2: Create the cloudbuild.yaml

In Cloud Shell, create the pipeline definition file in the project root:

```bash
cd ~/upi-transfer-service
```

Create a file named `cloudbuild.yaml` with the following content:

```yaml
steps:
  # Step 1: Build the application with Maven
  - name: 'maven:3.9-eclipse-temurin-17'
    entrypoint: 'mvn'
    args: ['package', '-DskipTests']

  # Step 2: Build the Docker image
  - name: 'gcr.io/cloud-builders/docker'
    args:
      - 'build'
      - '-t'
      - 'asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:$SHORT_SHA'
      - '-t'
      - 'asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:latest'
      - '.'

  # Step 3: Push the Docker image to Artifact Registry
  - name: 'gcr.io/cloud-builders/docker'
    args:
      - 'push'
      - '--all-tags'
      - 'asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service'

  # Step 4: Deploy to Cloud Run
  - name: 'gcr.io/google.com/cloudsdktool/cloud-sdk'
    entrypoint: 'gcloud'
    args:
      - 'run'
      - 'deploy'
      - 'upi-transfer-service'
      - '--image'
      - 'asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:$SHORT_SHA'
      - '--region'
      - 'asia-south1'
      - '--platform'
      - 'managed'
      - '--allow-unauthenticated'
      - '--port'
      - '8080'
      - '--memory'
      - '512Mi'

images:
  - 'asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:$SHORT_SHA'
  - 'asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo/upi-transfer-service:latest'

options:
  logging: CLOUD_LOGGING_ONLY
```

### Understanding each step

| Step | Builder Image | What It Does |
|------|--------------|-------------|
| 1 | `maven:3.9-eclipse-temurin-17` | Compiles Java code, creates the jar |
| 2 | `gcr.io/cloud-builders/docker` | Builds the Docker image with commit SHA and `latest` tags |
| 3 | `gcr.io/cloud-builders/docker` | Pushes both tagged images to Artifact Registry |
| 4 | `gcr.io/google.com/cloudsdktool/cloud-sdk` | Deploys the new image to Cloud Run |

### Key variables

| Variable | Source | Example Value |
|----------|--------|--------------|
| `$PROJECT_ID` | Automatically set by Cloud Build | `upi-workshop-438207` |
| `$SHORT_SHA` | Git commit short hash (7 chars) | `a1b2c3d` |

> `$SHORT_SHA` is automatically populated when triggered from a Git repository. For manual builds, you can set a custom substitution.

---

## Part 3: Trigger a Manual Build

```bash
cd ~/upi-transfer-service

# Submit a build manually
gcloud builds submit \
  --config=cloudbuild.yaml \
  --substitutions=SHORT_SHA=manual01 \
  .
```

> The `.` at the end means "upload the current directory as the build source."

### Watch the build

```bash
# List recent builds
gcloud builds list --limit=5

# Stream logs of the latest build
gcloud builds log $(gcloud builds list --limit=1 --format='value(id)')
```

### Monitor in Console

1. Go to **Navigation Menu > Cloud Build > History**
2. Click on the running build
3. You should see each step executing in sequence with real-time logs

### Verify the deployment

```bash
export SERVICE_URL=$(gcloud run services describe upi-transfer-service \
  --region asia-south1 --format='value(status.url)')

# Test the live service
curl -s $SERVICE_URL/api/balance/nag@upi | python3 -m json.tool
```

You should see the balance response, confirming the pipeline deployed successfully.

### Verify the image in Artifact Registry

```bash
gcloud artifacts docker images list \
  asia-south1-docker.pkg.dev/$PROJECT_ID/upi-repo \
  --format="table(package, tags, createTime)"
```

You should see the image tagged with `manual01` and `latest`.

---

## Part 4: Set Up a GitHub Trigger

Now let's automate builds on every code push.

### Step 1: Push your code to GitHub (if not already)

```bash
cd ~/upi-transfer-service

# Initialize git if needed
git init
git add .
git commit -m "Initial commit with Cloud Build pipeline"

# Create a GitHub repo and push (replace with your username)
# Option A: Using GitHub CLI
gh repo create upi-transfer-service --public --source=. --push

# Option B: Manual
# Create repo on github.com, then:
# git remote add origin https://github.com/YOUR_USERNAME/upi-transfer-service.git
# git push -u origin main
```

### Step 2: Connect GitHub to Cloud Build

1. Go to **Navigation Menu > Cloud Build > Triggers**
2. Click **Connect Repository**
3. Select **GitHub (Cloud Build GitHub App)**
4. Authenticate and select your repository `upi-transfer-service`
5. Click **Connect**

### Step 3: Create the trigger

1. Click **Create Trigger**
2. Configure:

| Setting | Value |
|---------|-------|
| **Name** | `upi-service-deploy` |
| **Event** | Push to a branch |
| **Source** | Your connected repository |
| **Branch** | `^main$` |
| **Configuration** | Cloud Build configuration file |
| **Location** | `/cloudbuild.yaml` |

3. Click **Create**

### Step 4: Test the trigger

Make a small change and push:

```bash
cd ~/upi-transfer-service

# Make a small change
echo "# Triggered build test" >> src/main/resources/application.properties

git add .
git commit -m "Test Cloud Build trigger"
git push origin main
```

### Monitor the triggered build

1. Go to **Cloud Build > History**
2. You should see a new build triggered automatically
3. Watch it progress through all 4 steps

> The build takes 2-4 minutes. Once complete, the new version is live on Cloud Run.

---

## Part 5: Verify End-to-End Pipeline

```bash
export SERVICE_URL=$(gcloud run services describe upi-transfer-service \
  --region asia-south1 --format='value(status.url)')

# Verify the service is running the latest version
curl -s $SERVICE_URL/actuator/health | python3 -m json.tool

# Check the revision list — you should see a new revision
gcloud run revisions list \
  --service=upi-transfer-service \
  --region=asia-south1 \
  --format="table(name, active, creation_timestamp)"
```

### The complete flow

```
Developer pushes code to GitHub
        │
        ▼
GitHub trigger fires Cloud Build
        │
        ▼
Step 1: Maven builds the jar
        │
        ▼
Step 2: Docker builds the image
        │
        ▼
Step 3: Image pushed to Artifact Registry
        │
        ▼
Step 4: Cloud Run deploys the new image
        │
        ▼
Live service updated — zero downtime
```

---

## Checkpoint

- [ ] Cloud Build APIs enabled and IAM permissions granted
- [ ] Created `cloudbuild.yaml` with 4 pipeline steps
- [ ] Triggered a manual build and verified deployment
- [ ] Connected GitHub and created an automatic trigger
- [ ] Pushed code and watched the trigger fire
- [ ] Verified the end-to-end pipeline: push → build → deploy → live
