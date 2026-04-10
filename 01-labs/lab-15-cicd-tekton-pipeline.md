# Lab 15: CI/CD Pipeline with Tekton on GKE

**Duration:** 120 minutes
**Level:** Intermediate
**Prerequisites:** GKE cluster running, kubectl configured, gcloud SDK installed

---

## Objective

Build a complete CI/CD pipeline using **Tekton** on GKE that automatically builds, tests, containerizes, and deploys the `upi-transfer-service` to Cloud Run.

By the end of this lab you will:

- Install Tekton Pipelines and Dashboard on GKE
- Create custom Tekton Tasks and Pipelines
- Build Docker images inside Kubernetes using **Kaniko** (no Docker daemon needed)
- Push images to Google Artifact Registry
- Deploy to Cloud Run from a Tekton pipeline
- Monitor pipeline runs via Tekton Dashboard and the `tkn` CLI

---

## Architecture Overview

```
Developer --> git push --> GKE Cluster (Tekton)
                            |
                            +-- Task 1: Clone repo (git-clone)
                            |
                            +-- Task 2: Maven build & test (maven:3.9-eclipse-temurin-17)
                            |
                            +-- Task 3: Kaniko build & push image (kaniko-project/executor)
                            |
                            +-- Task 4: Deploy to Cloud Run (google/cloud-sdk)
                                         |
                                         v
                                 Cloud Run Service
                              (upi-transfer-service)
                                   Port 8080
                              /actuator/health  <-- health check
                              /api/transfer     <-- POST
                              /api/balance/{id} <-- GET
```

**Why Tekton?**

| Feature | Tekton | Jenkins | GitHub Actions |
|---------|--------|---------|----------------|
| Runs on Kubernetes | Yes (native) | Plugin required | No |
| Cloud-native CRDs | Yes | No | No |
| No single point of failure | Yes | No (controller) | N/A (hosted) |
| Scales with cluster | Yes | Limited | N/A |
| Vendor lock-in | None | None | GitHub |

---

## Part 1: Create GKE Cluster & Install Tekton (20 min)

### Step 1.1: Set environment variables

Set these variables once -- every command in this lab references them.

```bash
export PROJECT_ID=upi-workshop-492606
export REGION=us-central1
export ZONE=us-central1-a
export CLUSTER_NAME=tekton-cicd-cluster
export REPO_NAME=upi-repo
export REGISTRY=$REGION-docker.pkg.dev/$PROJECT_ID/$REPO_NAME
```

Verify the project is set correctly:

```bash
gcloud config set project $PROJECT_ID
```

**Expected output:**
```
Updated property [core/project].
```

### Step 1.2: Enable required APIs

```bash
gcloud services enable \
  container.googleapis.com \
  artifactregistry.googleapis.com \
  run.googleapis.com \
  iam.googleapis.com \
  cloudresourcemanager.googleapis.com
```

**Expected output:**
```
Operation "operations/acat.p2-xxxx" finished successfully.
```

> **Note:** This may take 1-2 minutes. If the APIs are already enabled, the command returns immediately.

### Step 1.3: Create a GKE cluster (if not already running)

If you already have a GKE cluster from Lab 14, skip to Step 1.4. Otherwise, create one:

```bash
gcloud container clusters create $CLUSTER_NAME \
  --zone $ZONE \
  --num-nodes 3 \
  --machine-type e2-standard-4 \
  --disk-size 50GB \
  --enable-ip-alias \
  --scopes cloud-platform
```

**Expected output:**
```
Creating cluster tekton-cicd-cluster in us-central1-a...
...
NAME                  LOCATION       MASTER_VERSION   MASTER_IP      MACHINE_TYPE    NODE_VERSION     NUM_NODES  STATUS
tekton-cicd-cluster   us-central1-a  1.31.4-gke.100   34.72.xx.xxx   e2-standard-4   1.31.4-gke.100   3          RUNNING
```

> **Why e2-standard-4?** Tekton pipelines run build steps as pods. Maven builds and Kaniko image builds need at least 2 CPU and 4 GB RAM per pod. With 3 nodes of e2-standard-4 (4 vCPU, 16 GB each), the cluster has plenty of headroom.

### Step 1.4: Get cluster credentials

```bash
gcloud container clusters get-credentials $CLUSTER_NAME --zone $ZONE
```

**Expected output:**
```
Fetching cluster endpoint and auth data.
kubeconfig entry generated for tekton-cicd-cluster.
```

Verify connectivity:

```bash
kubectl cluster-info
```

**Expected output:**
```
Kubernetes control plane is running at https://34.72.xx.xxx
GLBCDefaultBackend is running at https://34.72.xx.xxx/api/v1/...
KubeDNS is running at https://34.72.xx.xxx/api/v1/...
```

### Step 1.5: Install Tekton Pipelines

```bash
kubectl apply --filename https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml
```

**Expected output:**
```
namespace/tekton-pipelines created
clusterrole.rbac.authorization.k8s.io/tekton-pipelines-controller-cluster-access created
clusterrole.rbac.authorization.k8s.io/tekton-pipelines-controller-tenant-access created
...
deployment.apps/tekton-pipelines-controller created
deployment.apps/tekton-pipelines-webhook created
...
```

Wait for the Tekton pods to become ready:

```bash
kubectl get pods -n tekton-pipelines --watch
```

**Expected output (wait until both show `Running` with `1/1`):**
```
NAME                                           READY   STATUS    RESTARTS   AGE
tekton-pipelines-controller-7f4f5dcf9b-x8k2j   1/1     Running   0          45s
tekton-pipelines-webhook-5c9d6c8f7d-m4n7p       1/1     Running   0          45s
```

Press `Ctrl+C` to stop watching once both pods are Running.

### Step 1.6: Install Tekton Dashboard

```bash
kubectl apply --filename https://storage.googleapis.com/tekton-releases/dashboard/latest/release.yaml
```

**Expected output:**
```
namespace/tekton-pipelines unchanged
serviceaccount/tekton-dashboard created
...
deployment.apps/tekton-dashboard created
service/tekton-dashboard created
```

Verify the dashboard pod is running:

```bash
kubectl get pods -n tekton-pipelines -l app=tekton-dashboard
```

**Expected output:**
```
NAME                                READY   STATUS    RESTARTS   AGE
tekton-dashboard-6c7f5c4b9d-r2t5k   1/1     Running   0          30s
```

Start port-forwarding to access the dashboard (run this in a separate terminal):

```bash
kubectl port-forward -n tekton-pipelines service/tekton-dashboard 9097:9097
```

**Expected output:**
```
Forwarding from 127.0.0.1:9097 -> 9097
Forwarding from [::1]:9097 -> 9097
```

Open your browser and navigate to **http://localhost:9097**. You should see the Tekton Dashboard with an empty list of PipelineRuns.

> **Tip:** Keep this terminal open throughout the lab. If you close it, the dashboard becomes inaccessible.

### Step 1.7: Install Tekton CLI (tkn)

**macOS (Homebrew):**

```bash
brew install tektoncd-cli
```

**Linux:**

```bash
curl -LO https://github.com/tektoncd/cli/releases/latest/download/tkn_Linux_x86_64.tar.gz
tar xvzf tkn_Linux_x86_64.tar.gz -C /usr/local/bin/ tkn
rm tkn_Linux_x86_64.tar.gz
```

Verify the installation:

```bash
tkn version
```

**Expected output:**
```
Client version: 0.39.0
Pipeline version: v0.62.0
Dashboard version: v0.52.0
```

> **Note:** Your exact version numbers may differ. The important thing is that `tkn` can communicate with your cluster and detect the pipeline and dashboard versions.

### Understanding what just happened

When you installed Tekton, Kubernetes received several **Custom Resource Definitions (CRDs)** that extend the Kubernetes API with CI/CD concepts:

| CRD | Description | Analogy |
|-----|-------------|---------|
| `Task` | A series of steps that run sequentially in one pod | A function |
| `TaskRun` | A single execution of a Task | A function call |
| `Pipeline` | An ordered collection of Tasks | A program |
| `PipelineRun` | A single execution of a Pipeline | A program execution |
| `Workspace` | A shared volume between Tasks | A shared filesystem |

Verify the CRDs are installed:

```bash
kubectl get crds | grep tekton
```

**Expected output:**
```
clustertasks.tekton.dev                     2026-04-10T...
customruns.tekton.dev                       2026-04-10T...
pipelineresources.tekton.dev                2026-04-10T...
pipelineruns.tekton.dev                     2026-04-10T...
pipelines.tekton.dev                        2026-04-10T...
resolutionrequests.resolution.tekton.dev    2026-04-10T...
runs.tekton.dev                             2026-04-10T...
taskruns.tekton.dev                         2026-04-10T...
tasks.tekton.dev                            2026-04-10T...
verificationpolicies.tekton.dev             2026-04-10T...
```

---

## Part 2: Set Up Artifact Registry & Service Account (10 min)

### Step 2.1: Create an Artifact Registry repository

If you already have `upi-repo` from Lab 14, skip this step.

```bash
gcloud artifacts repositories create $REPO_NAME \
  --repository-format=docker \
  --location=$REGION \
  --description="UPI Service Docker images"
```

**Expected output:**
```
Create request issued for: [upi-repo]
Waiting for operation...done.
Created repository [upi-repo].
```

Verify the repository exists:

```bash
gcloud artifacts repositories list --location=$REGION
```

**Expected output:**
```
REPOSITORY  FORMAT  MODE                 DESCRIPTION                  LOCATION       LABELS  ENCRYPTION          CREATE_TIME          UPDATE_TIME
upi-repo    DOCKER  STANDARD_REPOSITORY  UPI Service Docker images    us-central1            Google-managed key  2026-04-10T...       2026-04-10T...
```

### Step 2.2: Create a service account for Tekton

Tekton tasks run inside Kubernetes pods. These pods need GCP permissions to push images to Artifact Registry and deploy to Cloud Run. We create a dedicated service account for this.

```bash
gcloud iam service-accounts create tekton-sa \
  --display-name="Tekton CI/CD Service Account"
```

**Expected output:**
```
Created service account [tekton-sa].
```

Grant the required roles:

```bash
# Allow pushing Docker images to Artifact Registry
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:tekton-sa@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.writer"

# Allow deploying services to Cloud Run
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:tekton-sa@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/run.admin"

# Allow acting as a service account (needed for Cloud Run deployment)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:tekton-sa@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountUser"
```

**Expected output (for each command):**
```
Updated IAM policy for project [upi-workshop-492606].
bindings:
- members:
  - serviceAccount:tekton-sa@upi-workshop-492606.iam.gserviceaccount.com
  role: roles/artifactregistry.writer
...
```

### Step 2.3: Create a service account key and store as Kubernetes secret

```bash
gcloud iam service-accounts keys create tekton-sa-key.json \
  --iam-account=tekton-sa@${PROJECT_ID}.iam.gserviceaccount.com
```

**Expected output:**
```
created key [a1b2c3d4...] of type [json] as [tekton-sa-key.json] for [tekton-sa@upi-workshop-492606.iam.gserviceaccount.com]
```

Now create a Kubernetes secret from this key. Tekton tasks will mount this secret to authenticate with GCP:

```bash
kubectl create secret generic gcp-credentials \
  --from-file=key.json=tekton-sa-key.json
```

**Expected output:**
```
secret/gcp-credentials created
```

We also need a Docker config secret for Kaniko to push images to Artifact Registry:

```bash
kubectl create secret docker-registry artifact-registry-secret \
  --docker-server=us-central1-docker.pkg.dev \
  --docker-username=_json_key \
  --docker-password="$(cat tekton-sa-key.json)" \
  --docker-email=tekton-sa@${PROJECT_ID}.iam.gserviceaccount.com
```

**Expected output:**
```
secret/artifact-registry-secret created
```

Verify both secrets exist:

```bash
kubectl get secrets
```

**Expected output:**
```
NAME                        TYPE                             DATA   AGE
artifact-registry-secret    kubernetes.io/dockerconfigjson   1      10s
gcp-credentials             Opaque                           1      30s
```

> **Security Note:** The `tekton-sa-key.json` file contains sensitive credentials. In a production environment, you would use **Workload Identity** instead of service account keys. We use keys here for simplicity in the lab.

Clean up the local key file:

```bash
rm tekton-sa-key.json
```

---

## Part 3: Create Tekton Tasks (30 min)

In this section, we create four Tekton Tasks. Each task is a Kubernetes Custom Resource that defines a series of steps to run inside a pod.

### Step 3.1: Create a workspace PVC

All tasks share a **workspace** -- a persistent volume that holds the source code, build artifacts, and anything else that needs to pass between tasks.

Create a file called `tekton/workspace-pvc.yaml`:

```bash
mkdir -p tekton
```

```bash
cat > tekton/workspace-pvc.yaml << 'EOF'
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: tekton-workspace-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 2Gi
  storageClassName: standard-rwo
EOF
```

Apply it:

```bash
kubectl apply -f tekton/workspace-pvc.yaml
```

**Expected output:**
```
persistentvolumeclaim/tekton-workspace-pvc created
```

Verify the PVC is bound:

```bash
kubectl get pvc tekton-workspace-pvc
```

**Expected output:**
```
NAME                   STATUS   VOLUME                                     CAPACITY   ACCESS MODES   STORAGECLASS    AGE
tekton-workspace-pvc   Bound    pvc-a1b2c3d4-e5f6-7890-abcd-ef1234567890   2Gi        RWO            standard-rwo    5s
```

> **Why ReadWriteOnce?** Tekton runs tasks sequentially by default (using `runAfter`). Since only one pod uses the volume at a time, `ReadWriteOnce` is sufficient and cheaper than `ReadWriteMany`.

### Step 3.2: Task 1 -- Clone Repository

This task clones the Git repository into the shared workspace so subsequent tasks can access the source code.

Create `tekton/task-git-clone.yaml`:

```bash
cat > tekton/task-git-clone.yaml << 'EOF'
apiVersion: tekton.dev/v1
kind: Task
metadata:
  name: git-clone
  labels:
    app.kubernetes.io/version: "1.0"
  annotations:
    tekton.dev/pipelines.minVersion: "0.50.0"
    tekton.dev/tags: git
spec:
  description: >-
    Clones a Git repository into the workspace.
    Used as the first step in the CI/CD pipeline.
  params:
    - name: repo-url
      type: string
      description: The URL of the Git repository to clone
    - name: revision
      type: string
      description: The branch, tag, or commit SHA to checkout
      default: main
    - name: subdirectory
      type: string
      description: Subdirectory inside the workspace to clone into
      default: ""
  workspaces:
    - name: source
      description: The workspace where the repository will be cloned
  steps:
    - name: clone
      image: alpine/git:2.43.0
      script: |
        #!/usr/bin/env sh
        set -eu

        echo "============================================"
        echo "  TASK: git-clone"
        echo "  Cloning $(params.repo-url)"
        echo "  Branch: $(params.revision)"
        echo "============================================"

        CHECKOUT_DIR="$(workspaces.source.path)/$(params.subdirectory)"

        # Clean workspace if previous run left files
        rm -rf "${CHECKOUT_DIR}"
        mkdir -p "${CHECKOUT_DIR}"

        cd "${CHECKOUT_DIR}"

        git clone --branch $(params.revision) --depth 1 \
          $(params.repo-url) .

        echo ""
        echo "Clone complete. Files in workspace:"
        ls -la
        echo ""
        echo "Git log:"
        git log --oneline -1

        COMMIT_SHA=$(git rev-parse HEAD)
        echo ""
        echo "Commit SHA: ${COMMIT_SHA}"
  results:
    - name: commit-sha
      description: The SHA of the cloned commit
EOF
```

Apply and verify:

```bash
kubectl apply -f tekton/task-git-clone.yaml
```

**Expected output:**
```
task.tekton.dev/git-clone created
```

```bash
tkn task list
```

**Expected output:**
```
NAME        DESCRIPTION                                       AGE
git-clone   Clones a Git repository into the workspace....    5s
```

### Step 3.3: Task 2 -- Maven Build & Test

This task compiles the Java source code, runs unit tests, and packages the application into a JAR file.

Create `tekton/task-maven-build.yaml`:

```bash
cat > tekton/task-maven-build.yaml << 'EOF'
apiVersion: tekton.dev/v1
kind: Task
metadata:
  name: maven-build
  labels:
    app.kubernetes.io/version: "1.0"
  annotations:
    tekton.dev/pipelines.minVersion: "0.50.0"
    tekton.dev/tags: build, java, maven
spec:
  description: >-
    Runs Maven build and tests on a Java project.
    Uses the Maven wrapper (mvnw) if available.
  params:
    - name: subdirectory
      type: string
      description: Subdirectory of the workspace containing the project
      default: ""
    - name: maven-goals
      type: string
      description: Maven goals to execute
      default: "clean package"
    - name: skip-tests
      type: string
      description: Whether to skip tests (true/false)
      default: "false"
  workspaces:
    - name: source
      description: The workspace containing the cloned source code
  steps:
    - name: maven-build
      image: maven:3.9-eclipse-temurin-17
      workingDir: $(workspaces.source.path)/$(params.subdirectory)
      script: |
        #!/usr/bin/env bash
        set -eu

        echo "============================================"
        echo "  TASK: maven-build"
        echo "  Goals: $(params.maven-goals)"
        echo "  Skip Tests: $(params.skip-tests)"
        echo "============================================"

        echo ""
        echo "--- Java version ---"
        java -version

        echo ""
        echo "--- Maven version ---"
        mvn --version

        echo ""
        echo "--- Project directory ---"
        ls -la

        echo ""
        echo "--- Running Maven build ---"

        # Use mvnw if available, otherwise fall back to mvn
        if [ -f "./mvnw" ]; then
          chmod +x ./mvnw
          ./mvnw $(params.maven-goals) -DskipTests=$(params.skip-tests) -B
        else
          mvn $(params.maven-goals) -DskipTests=$(params.skip-tests) -B
        fi

        echo ""
        echo "--- Build artifacts ---"
        ls -la target/*.jar

        echo ""
        echo "BUILD SUCCESSFUL"
      resources:
        requests:
          memory: "1Gi"
          cpu: "500m"
        limits:
          memory: "2Gi"
          cpu: "2000m"
EOF
```

Apply and verify:

```bash
kubectl apply -f tekton/task-maven-build.yaml
```

**Expected output:**
```
task.tekton.dev/maven-build created
```

> **Why `maven:3.9-eclipse-temurin-17`?** The upi-transfer-service uses Java 17 (specified in pom.xml as `<java.version>17</java.version>`) and Spring Boot 3.4.4. This image provides both Maven 3.9 and the Eclipse Temurin JDK 17, matching the project's build requirements.

### Step 3.4: Task 3 -- Build & Push Docker Image with Kaniko

This task builds a Docker image from the Dockerfile and pushes it to Google Artifact Registry. It uses **Kaniko**, which builds container images inside a Kubernetes pod without needing a Docker daemon.

Create `tekton/task-kaniko-build.yaml`:

```bash
cat > tekton/task-kaniko-build.yaml << 'EOF'
apiVersion: tekton.dev/v1
kind: Task
metadata:
  name: kaniko-build
  labels:
    app.kubernetes.io/version: "1.0"
  annotations:
    tekton.dev/pipelines.minVersion: "0.50.0"
    tekton.dev/tags: image-build, kaniko
spec:
  description: >-
    Builds a Docker image using Kaniko and pushes it to a container registry.
    Kaniko runs in user-space and does not require a Docker daemon.
  params:
    - name: image-name
      type: string
      description: Full image path including registry (e.g., us-central1-docker.pkg.dev/project/repo/image:tag)
    - name: dockerfile
      type: string
      description: Path to the Dockerfile relative to the context
      default: Dockerfile
    - name: context
      type: string
      description: Build context directory relative to the workspace
      default: ""
  workspaces:
    - name: source
      description: The workspace containing the source code and Dockerfile
  steps:
    - name: build-and-push
      image: gcr.io/kaniko-project/executor:latest
      args:
        - --dockerfile=$(workspaces.source.path)/$(params.context)/$(params.dockerfile)
        - --context=$(workspaces.source.path)/$(params.context)
        - --destination=$(params.image-name)
        - --cache=true
        - --cache-ttl=24h
        - --snapshot-mode=redo
        - --log-format=text
      volumeMounts:
        - name: docker-config
          mountPath: /kaniko/.docker
      resources:
        requests:
          memory: "1Gi"
          cpu: "500m"
        limits:
          memory: "2Gi"
          cpu: "2000m"
  volumes:
    - name: docker-config
      secret:
        secretName: artifact-registry-secret
        items:
          - key: .dockerconfigjson
            path: config.json
EOF
```

Apply and verify:

```bash
kubectl apply -f tekton/task-kaniko-build.yaml
```

**Expected output:**
```
task.tekton.dev/kaniko-build created
```

> **Why Kaniko instead of Docker?**
>
> | Approach | Docker daemon required? | Privileged container? | Kubernetes-native? |
> |----------|------------------------|-----------------------|-------------------|
> | Docker-in-Docker (DinD) | Yes | Yes (security risk) | No |
> | Docker socket mount | Yes (host daemon) | Yes (security risk) | No |
> | **Kaniko** | **No** | **No** | **Yes** |
>
> Kaniko builds the image layer by layer in userspace, making it safe and ideal for Kubernetes CI/CD.

### Step 3.5: Task 4 -- Deploy to Cloud Run

This task deploys the built container image to Cloud Run.

Create `tekton/task-deploy-cloudrun.yaml`:

```bash
cat > tekton/task-deploy-cloudrun.yaml << 'EOF'
apiVersion: tekton.dev/v1
kind: Task
metadata:
  name: deploy-cloudrun
  labels:
    app.kubernetes.io/version: "1.0"
  annotations:
    tekton.dev/pipelines.minVersion: "0.50.0"
    tekton.dev/tags: deploy, cloud-run, gcp
spec:
  description: >-
    Deploys a container image to Google Cloud Run.
    Uses the gcloud CLI with a service account for authentication.
  params:
    - name: service-name
      type: string
      description: Name of the Cloud Run service
    - name: image-name
      type: string
      description: Full image path to deploy
    - name: region
      type: string
      description: GCP region for Cloud Run
      default: us-central1
    - name: project-id
      type: string
      description: GCP project ID
    - name: port
      type: string
      description: Port the container listens on
      default: "8080"
    - name: memory
      type: string
      description: Memory allocation for the Cloud Run service
      default: "512Mi"
    - name: max-instances
      type: string
      description: Maximum number of instances
      default: "3"
  workspaces:
    - name: source
      description: The workspace (not used by this task, but required for pipeline workspace binding)
  steps:
    - name: deploy
      image: google/cloud-sdk:slim
      script: |
        #!/usr/bin/env bash
        set -eu

        echo "============================================"
        echo "  TASK: deploy-cloudrun"
        echo "  Service: $(params.service-name)"
        echo "  Image: $(params.image-name)"
        echo "  Region: $(params.region)"
        echo "  Project: $(params.project-id)"
        echo "============================================"

        echo ""
        echo "--- Authenticating with GCP ---"
        gcloud auth activate-service-account \
          --key-file=/var/secrets/google/key.json

        gcloud config set project $(params.project-id)

        echo ""
        echo "--- Deploying to Cloud Run ---"
        gcloud run deploy $(params.service-name) \
          --image=$(params.image-name) \
          --region=$(params.region) \
          --platform=managed \
          --port=$(params.port) \
          --memory=$(params.memory) \
          --max-instances=$(params.max-instances) \
          --allow-unauthenticated \
          --quiet

        echo ""
        echo "--- Verifying deployment ---"
        SERVICE_URL=$(gcloud run services describe $(params.service-name) \
          --region=$(params.region) \
          --format='value(status.url)')

        echo "Service URL: ${SERVICE_URL}"
        echo ""
        echo "--- Checking health endpoint ---"
        sleep 10
        HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${SERVICE_URL}/actuator/health" || echo "000")

        if [ "${HTTP_STATUS}" = "200" ]; then
          echo "Health check PASSED (HTTP ${HTTP_STATUS})"
        else
          echo "Health check returned HTTP ${HTTP_STATUS}"
          echo "The service may still be starting up. Check manually:"
          echo "  curl ${SERVICE_URL}/actuator/health"
        fi

        echo ""
        echo "DEPLOYMENT COMPLETE"
        echo "Service URL: ${SERVICE_URL}"
      volumeMounts:
        - name: gcp-credentials
          mountPath: /var/secrets/google
          readOnly: true
  volumes:
    - name: gcp-credentials
      secret:
        secretName: gcp-credentials
EOF
```

Apply and verify:

```bash
kubectl apply -f tekton/task-deploy-cloudrun.yaml
```

**Expected output:**
```
task.tekton.dev/deploy-cloudrun created
```

### Step 3.6: Verify all tasks

```bash
tkn task list
```

**Expected output:**
```
NAME              DESCRIPTION                                          AGE
deploy-cloudrun   Deploys a container image to Google Cloud Run....     10s
git-clone         Clones a Git repository into the workspace....       3m
kaniko-build      Builds a Docker image using Kaniko and push...       1m
maven-build       Runs Maven build and tests on a Java project....     2m
```

You can inspect any task in detail:

```bash
tkn task describe maven-build
```

**Expected output:**
```
Name:        maven-build
Namespace:   default
Description: Runs Maven build and tests on a Java project. Uses the Maven wrapper (mvnw) if available.

📦 Params
 NAME            TYPE     DESCRIPTION                                          DEFAULT VALUE
 subdirectory    string   Subdirectory of the workspace containing the pr...   ---
 maven-goals     string   Maven goals to execute                               clean package
 skip-tests      string   Whether to skip tests (true/false)                   false

📡 Results
 No results

📂 Workspaces
 NAME     DESCRIPTION
 source   The workspace containing the cloned source code

🦶 Steps
 NAME
 maven-build
```

### Understanding Tekton Tasks

| Task Concept | Programming Analogy | Example |
|-------------|---------------------|---------|
| `params` | Function parameters | `repo-url`, `image-name` |
| `workspaces` | Shared filesystem / arguments by reference | Source code volume |
| `steps` | Lines of code in a function body | `git clone`, `mvn package` |
| `results` | Return values | `commit-sha` |
| `image` (per step) | The runtime environment | `maven:3.9-eclipse-temurin-17` |

---

## Part 4: Create the Pipeline (15 min)

Now we connect the four tasks into a **Pipeline** -- an ordered sequence of tasks with dependencies.

### Step 4.1: Create the Pipeline YAML

Create `tekton/pipeline-upi-cicd.yaml`:

```bash
cat > tekton/pipeline-upi-cicd.yaml << 'EOF'
apiVersion: tekton.dev/v1
kind: Pipeline
metadata:
  name: upi-cicd-pipeline
  labels:
    app.kubernetes.io/version: "1.0"
  annotations:
    tekton.dev/pipelines.minVersion: "0.50.0"
spec:
  description: >-
    CI/CD pipeline for the UPI Transfer Service.
    Clones the repository, builds with Maven, creates a Docker image
    with Kaniko, and deploys to Cloud Run.

  params:
    - name: repo-url
      type: string
      description: Git repository URL
    - name: revision
      type: string
      description: Git branch or tag
      default: main
    - name: image-name
      type: string
      description: Full image name with tag (e.g., us-central1-docker.pkg.dev/project/repo/image:tag)
    - name: service-name
      type: string
      description: Cloud Run service name
      default: upi-transfer-service
    - name: project-id
      type: string
      description: GCP project ID
      default: upi-workshop-492606
    - name: region
      type: string
      description: GCP region
      default: us-central1

  workspaces:
    - name: shared-workspace
      description: Shared workspace for source code and build artifacts

  tasks:
    # ---- Task 1: Clone the Git repository ----
    - name: clone-repo
      taskRef:
        name: git-clone
      params:
        - name: repo-url
          value: $(params.repo-url)
        - name: revision
          value: $(params.revision)
        - name: subdirectory
          value: ""
      workspaces:
        - name: source
          workspace: shared-workspace

    # ---- Task 2: Build and test with Maven ----
    - name: build-and-test
      taskRef:
        name: maven-build
      runAfter:
        - clone-repo
      params:
        - name: subdirectory
          value: ""
        - name: maven-goals
          value: "clean package"
        - name: skip-tests
          value: "false"
      workspaces:
        - name: source
          workspace: shared-workspace

    # ---- Task 3: Build Docker image with Kaniko ----
    - name: build-image
      taskRef:
        name: kaniko-build
      runAfter:
        - build-and-test
      params:
        - name: image-name
          value: $(params.image-name)
        - name: dockerfile
          value: Dockerfile
        - name: context
          value: ""
      workspaces:
        - name: source
          workspace: shared-workspace

    # ---- Task 4: Deploy to Cloud Run ----
    - name: deploy-to-cloudrun
      taskRef:
        name: deploy-cloudrun
      runAfter:
        - build-image
      params:
        - name: service-name
          value: $(params.service-name)
        - name: image-name
          value: $(params.image-name)
        - name: region
          value: $(params.region)
        - name: project-id
          value: $(params.project-id)
        - name: port
          value: "8080"
        - name: memory
          value: "512Mi"
        - name: max-instances
          value: "3"
      workspaces:
        - name: source
          workspace: shared-workspace
EOF
```

> **Key concept: `runAfter`**
>
> The `runAfter` field creates a dependency chain:
> ```
> clone-repo --> build-and-test --> build-image --> deploy-to-cloudrun
> ```
> Without `runAfter`, Tekton would try to run all tasks in parallel. Since each task depends on the output of the previous one (source code, JAR file, Docker image), we must enforce sequential execution.

### Step 4.2: Apply the pipeline

```bash
kubectl apply -f tekton/pipeline-upi-cicd.yaml
```

**Expected output:**
```
pipeline.tekton.dev/upi-cicd-pipeline created
```

### Step 4.3: Verify the pipeline

```bash
tkn pipeline list
```

**Expected output:**
```
NAME                AGE             LAST RUN   STARTED   DURATION   STATUS
upi-cicd-pipeline   10 seconds ago  ---        ---       ---        ---
```

Inspect the pipeline details:

```bash
tkn pipeline describe upi-cicd-pipeline
```

**Expected output:**
```
Name:        upi-cicd-pipeline
Namespace:   default
Description: CI/CD pipeline for the UPI Transfer Service. Clones the repository, builds with Maven, creates a Docker image with Kaniko, and deploys to Cloud Run.

📦 Params
 NAME           TYPE     DESCRIPTION                      DEFAULT VALUE
 repo-url       string   Git repository URL               ---
 revision       string   Git branch or tag                 main
 image-name     string   Full image name with tag          ---
 service-name   string   Cloud Run service name            upi-transfer-service
 project-id     string   GCP project ID                   upi-workshop-492606
 region         string   GCP region                       us-central1

📂 Workspaces
 NAME               DESCRIPTION
 shared-workspace   Shared workspace for source code and build artifacts

🗒  Tasks
 NAME                 TASKREF           RUNAFTER
 clone-repo           git-clone         ---
 build-and-test       maven-build       clone-repo
 build-image          kaniko-build      build-and-test
 deploy-to-cloudrun   deploy-cloudrun   build-image

⛩  PipelineRuns
 No pipelineruns
```

---

## Part 5: Run the Pipeline (15 min)

### Step 5.1: Push your code to a Git repository

Tekton clones code from a Git repository. If your `upi-transfer-service` is not already in a Git repo, push it now.

> **Option A: Use your existing GitHub/GitLab repo.** If you already have the code pushed, use that URL.
>
> **Option B: Create a new repo on GitHub.**

If you need to create a new repo:

```bash
cd /Users/nag/ford-phase6/upi-transfer-service

# Initialize git if not already done
git init
git add .
git commit -m "Initial commit: upi-transfer-service"

# Create a repo on GitHub (requires gh CLI)
gh repo create upi-transfer-service --public --source=. --push
```

> **Note:** Replace the repository URL in the next step with your actual Git repository URL.

### Step 5.2: Create a PipelineRun using the tkn CLI

This is the moment we have been building toward -- running the entire pipeline.

```bash
export GIT_REPO_URL=https://github.com/<your-username>/upi-transfer-service.git
export IMAGE_TAG=$(date +%Y%m%d%H%M%S)
export IMAGE_NAME=${REGISTRY}/upi-transfer-service:${IMAGE_TAG}

echo "Image will be: ${IMAGE_NAME}"
```

**Expected output:**
```
Image will be: us-central1-docker.pkg.dev/upi-workshop-492606/upi-repo/upi-transfer-service:20260410143022
```

Start the pipeline:

```bash
tkn pipeline start upi-cicd-pipeline \
  --param repo-url=${GIT_REPO_URL} \
  --param revision=main \
  --param image-name=${IMAGE_NAME} \
  --param service-name=upi-transfer-service \
  --param project-id=${PROJECT_ID} \
  --param region=${REGION} \
  --workspace name=shared-workspace,claimName=tekton-workspace-pvc \
  --showlog
```

**Expected output (abbreviated -- full run takes 5-10 minutes):**

```
PipelineRun started: upi-cicd-pipeline-run-abcde
Waiting for logs to be available...

[clone-repo : clone] ============================================
[clone-repo : clone]   TASK: git-clone
[clone-repo : clone]   Cloning https://github.com/<your-username>/upi-transfer-service.git
[clone-repo : clone]   Branch: main
[clone-repo : clone] ============================================
[clone-repo : clone] Cloning into '.'...
[clone-repo : clone]
[clone-repo : clone] Clone complete. Files in workspace:
[clone-repo : clone] total 28
[clone-repo : clone] drwxr-xr-x  6 root root  4096 Apr 10 14:31 .
[clone-repo : clone] drwxr-xr-x  3 root root  4096 Apr 10 14:31 ..
[clone-repo : clone] drwxr-xr-x  8 root root  4096 Apr 10 14:31 .git
[clone-repo : clone] -rw-r--r--  1 root root   345 Apr 10 14:31 Dockerfile
[clone-repo : clone] -rw-r--r--  1 root root  1836 Apr 10 14:31 pom.xml
[clone-repo : clone] drwxr-xr-x  4 root root  4096 Apr 10 14:31 src
[clone-repo : clone] -rwxr-xr-x  1 root root 10284 Apr 10 14:31 mvnw
[clone-repo : clone]
[clone-repo : clone] Git log:
[clone-repo : clone] df44eca Initial commit: upi-transfer-service

[build-and-test : maven-build] ============================================
[build-and-test : maven-build]   TASK: maven-build
[build-and-test : maven-build]   Goals: clean package
[build-and-test : maven-build]   Skip Tests: false
[build-and-test : maven-build] ============================================
[build-and-test : maven-build]
[build-and-test : maven-build] --- Java version ---
[build-and-test : maven-build] openjdk version "17.0.13" 2024-10-15
[build-and-test : maven-build]
[build-and-test : maven-build] --- Maven version ---
[build-and-test : maven-build] Apache Maven 3.9.9
[build-and-test : maven-build]
[build-and-test : maven-build] --- Running Maven build ---
[build-and-test : maven-build] [INFO] Scanning for projects...
[build-and-test : maven-build] [INFO] --- maven-compiler-plugin:3.13.0:compile ---
[build-and-test : maven-build] [INFO] --- maven-surefire-plugin:3.5.2:test ---
[build-and-test : maven-build] [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[build-and-test : maven-build] [INFO] --- spring-boot-maven-plugin:3.4.4:repackage ---
[build-and-test : maven-build] [INFO] BUILD SUCCESS
[build-and-test : maven-build] [INFO] Total time: 45.123 s
[build-and-test : maven-build]
[build-and-test : maven-build] --- Build artifacts ---
[build-and-test : maven-build] -rw-r--r-- 1 root root 28456789 Apr 10 14:32 target/upi-transfer-service-0.0.1-SNAPSHOT.jar
[build-and-test : maven-build]
[build-and-test : maven-build] BUILD SUCCESSFUL

[build-image : build-and-push] INFO[0000] Resolved base name eclipse-temurin:17-jdk to build
[build-image : build-and-push] INFO[0000] Retrieving image manifest for eclipse-temurin:17-jdk
[build-image : build-and-push] INFO[0005] Built cross stage deps: map[0:[/app/target/*.jar]]
[build-image : build-and-push] INFO[0005] Retrieving image manifest for eclipse-temurin:17-jre
[build-image : build-and-push] INFO[0030] COPY --from=build /app/target/*.jar app.jar
[build-image : build-and-push] INFO[0030] EXPOSE 8080
[build-image : build-and-push] INFO[0030] ENTRYPOINT ["java", "-jar", "app.jar"]
[build-image : build-and-push] INFO[0035] Pushing image to us-central1-docker.pkg.dev/upi-workshop-492606/upi-repo/upi-transfer-service:20260410143022
[build-image : build-and-push] INFO[0045] Pushed image to 1 destinations

[deploy-to-cloudrun : deploy] ============================================
[deploy-to-cloudrun : deploy]   TASK: deploy-cloudrun
[deploy-to-cloudrun : deploy]   Service: upi-transfer-service
[deploy-to-cloudrun : deploy]   Image: us-central1-docker.pkg.dev/upi-workshop-492606/upi-repo/upi-transfer-service:20260410143022
[deploy-to-cloudrun : deploy]   Region: us-central1
[deploy-to-cloudrun : deploy]   Project: upi-workshop-492606
[deploy-to-cloudrun : deploy] ============================================
[deploy-to-cloudrun : deploy]
[deploy-to-cloudrun : deploy] --- Authenticating with GCP ---
[deploy-to-cloudrun : deploy] Activated service account credentials for: [tekton-sa@upi-workshop-492606.iam.gserviceaccount.com]
[deploy-to-cloudrun : deploy]
[deploy-to-cloudrun : deploy] --- Deploying to Cloud Run ---
[deploy-to-cloudrun : deploy] Deploying container to Cloud Run service [upi-transfer-service] in project [upi-workshop-492606] region [us-central1]
[deploy-to-cloudrun : deploy] OK Deploying... Done.
[deploy-to-cloudrun : deploy] Service [upi-transfer-service] revision [upi-transfer-service-00005-xyz] has been deployed
[deploy-to-cloudrun : deploy] Service URL: https://upi-transfer-service-abcdefghij-uc.a.run.app
[deploy-to-cloudrun : deploy]
[deploy-to-cloudrun : deploy] --- Checking health endpoint ---
[deploy-to-cloudrun : deploy] Health check PASSED (HTTP 200)
[deploy-to-cloudrun : deploy]
[deploy-to-cloudrun : deploy] DEPLOYMENT COMPLETE
[deploy-to-cloudrun : deploy] Service URL: https://upi-transfer-service-abcdefghij-uc.a.run.app
```

### Step 5.3: Alternatively, create a PipelineRun using YAML

If you prefer declarative YAML over the CLI, create `tekton/pipelinerun-upi.yaml`:

```bash
cat > tekton/pipelinerun-upi.yaml << 'EOF'
apiVersion: tekton.dev/v1
kind: PipelineRun
metadata:
  generateName: upi-cicd-pipeline-run-
spec:
  pipelineRef:
    name: upi-cicd-pipeline
  params:
    - name: repo-url
      value: "https://github.com/<your-username>/upi-transfer-service.git"
    - name: revision
      value: "main"
    - name: image-name
      value: "us-central1-docker.pkg.dev/upi-workshop-492606/upi-repo/upi-transfer-service:latest"
    - name: service-name
      value: "upi-transfer-service"
    - name: project-id
      value: "upi-workshop-492606"
    - name: region
      value: "us-central1"
  workspaces:
    - name: shared-workspace
      persistentVolumeClaim:
        claimName: tekton-workspace-pvc
EOF
```

> **Note:** Replace `<your-username>` with your actual GitHub username before applying.

Apply it:

```bash
kubectl create -f tekton/pipelinerun-upi.yaml
```

**Expected output:**
```
pipelinerun.tekton.dev/upi-cicd-pipeline-run-x7k2m created
```

> **Why `kubectl create` instead of `kubectl apply`?** The `generateName` field creates a unique name for each run. `kubectl apply` requires a fixed `name` field, while `kubectl create` works with `generateName`.

### Step 5.4: Monitor the pipeline run

Check the status:

```bash
tkn pipelinerun list
```

**Expected output:**
```
NAME                            STARTED          DURATION   STATUS
upi-cicd-pipeline-run-x7k2m    2 minutes ago    ---        Running
```

Follow the logs in real time:

```bash
tkn pipelinerun logs upi-cicd-pipeline-run-x7k2m -f
```

Check which task is currently running:

```bash
tkn pipelinerun describe upi-cicd-pipeline-run-x7k2m
```

**Expected output (while running):**
```
Name:        upi-cicd-pipeline-run-x7k2m
Namespace:   default
Pipeline:    upi-cicd-pipeline

Status
STARTED          DURATION   STATUS
2 minutes ago    ---        Running

Resources
 No resources

Params
 NAME           VALUE
 repo-url       https://github.com/<your-username>/upi-transfer-service.git
 revision       main
 image-name     us-central1-docker.pkg.dev/upi-workshop-492606/upi-repo/upi-transfer-service:latest
 service-name   upi-transfer-service
 project-id     upi-workshop-492606
 region         us-central1

Workspaces
 NAME               SUB PATH   WORKSPACE BINDING
 shared-workspace   ---        PersistentVolumeClaim (claimName=tekton-workspace-pvc)

Taskruns
 NAME                                             TASK NAME            STARTED          DURATION   STATUS
 upi-cicd-pipeline-run-x7k2m-clone-repo-xxxxx     clone-repo           3 minutes ago    15s        Succeeded
 upi-cicd-pipeline-run-x7k2m-build-and-test-xxx   build-and-test       2 minutes ago    1m30s      Succeeded
 upi-cicd-pipeline-run-x7k2m-build-image-xxxxx    build-image          30 seconds ago   ---        Running
```

### Step 5.5: Watch in Tekton Dashboard

If you still have the port-forward running from Step 1.6, open your browser to **http://localhost:9097**.

1. Click **PipelineRuns** in the left sidebar
2. You will see your pipeline run listed with its status
3. Click on the pipeline run name to see a **visual graph** of the four tasks
4. Each task shows as a box with a color indicating its status:
   - **Blue** = Running
   - **Green** = Succeeded
   - **Red** = Failed
   - **Gray** = Pending
5. Click on any task to see its step-level logs

### Step 5.6: Verify the deployment

Once the pipeline run shows `Succeeded`, verify the Cloud Run deployment:

```bash
gcloud run services describe upi-transfer-service \
  --region=us-central1 \
  --format='value(status.url)'
```

**Expected output:**
```
https://upi-transfer-service-abcdefghij-uc.a.run.app
```

Store the URL and test the API:

```bash
export SERVICE_URL=$(gcloud run services describe upi-transfer-service \
  --region=us-central1 \
  --format='value(status.url)')
```

Test the health endpoint:

```bash
curl ${SERVICE_URL}/actuator/health
```

**Expected output:**
```json
{"status":"UP"}
```

Test the balance endpoint:

```bash
curl ${SERVICE_URL}/api/balance/nag@upi
```

**Expected output:**
```json
{"upiId":"nag@upi","balance":10000.00}
```

Test the transfer endpoint:

```bash
curl -X POST ${SERVICE_URL}/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "ford@upi",
    "amount": 500
  }'
```

**Expected output:**
```json
{"transactionId":"txn-uuid-here","senderUpiId":"nag@upi","receiverUpiId":"ford@upi","amount":500,"status":"SUCCESS","timestamp":"2026-04-10T14:35:00"}
```

Verify the image in Artifact Registry:

```bash
gcloud artifacts docker images list ${REGISTRY}/upi-transfer-service \
  --include-tags \
  --limit=5
```

**Expected output:**
```
IMAGE                                                                                       DIGEST         TAGS             CREATE_TIME          UPDATE_TIME
us-central1-docker.pkg.dev/upi-workshop-492606/upi-repo/upi-transfer-service   sha256:abc123   20260410143022   2026-04-10T14:35:00  2026-04-10T14:35:00
```

---

## Part 6: Test the Full CI/CD Flow (10 min)

Now let us prove that the pipeline truly automates the full flow by making a change and rerunning it.

### Step 6.1: Make a code change

Let us add a version header to the API response. This is a small change we can easily verify after deployment.

Edit the main application class or any controller to add a log message:

```bash
cd /Users/nag/ford-phase6/upi-transfer-service
```

For example, if you have a `TransferController.java`, add a comment or change a default value. The exact change depends on your codebase. A simple approach is to update the application name in `application.yml` or `application.properties`:

```bash
echo "# Pipeline run: $(date)" >> src/main/resources/application.yml
```

Commit and push the change:

```bash
git add .
git commit -m "Add pipeline run timestamp"
git push origin main
```

### Step 6.2: Trigger a new pipeline run

```bash
export NEW_IMAGE_TAG=$(date +%Y%m%d%H%M%S)
export NEW_IMAGE_NAME=${REGISTRY}/upi-transfer-service:${NEW_IMAGE_TAG}

tkn pipeline start upi-cicd-pipeline \
  --param repo-url=${GIT_REPO_URL} \
  --param revision=main \
  --param image-name=${NEW_IMAGE_NAME} \
  --param service-name=upi-transfer-service \
  --param project-id=${PROJECT_ID} \
  --param region=${REGION} \
  --workspace name=shared-workspace,claimName=tekton-workspace-pvc \
  --showlog
```

Watch the entire pipeline execute again with the new code change.

### Step 6.3: Verify the new version is deployed

```bash
# Check that Cloud Run is serving the new revision
gcloud run revisions list \
  --service=upi-transfer-service \
  --region=us-central1 \
  --limit=3
```

**Expected output:**
```
REVISION                              ACTIVE  SERVICE                DEPLOYED                 LAST DEPLOYED BY
upi-transfer-service-00006-abc       yes     upi-transfer-service   2026-04-10 14:50:00 UTC  tekton-sa@upi-workshop-492606.iam.gserviceaccount.com
upi-transfer-service-00005-xyz       no      upi-transfer-service   2026-04-10 14:35:00 UTC  tekton-sa@upi-workshop-492606.iam.gserviceaccount.com
```

Test the service is still working:

```bash
curl ${SERVICE_URL}/actuator/health
```

**Expected output:**
```json
{"status":"UP"}
```

> **What we just proved:** A code change flowed automatically from Git through build, test, containerization, and deployment -- a complete CI/CD cycle managed by Tekton on GKE.

---

## Part 7: Pipeline Observability (10 min)

### Step 7.1: View pipeline run history

```bash
tkn pipelinerun list
```

**Expected output:**
```
NAME                                  STARTED           DURATION    STATUS
upi-cicd-pipeline-run-xyz123          5 minutes ago     8m30s       Succeeded
upi-cicd-pipeline-run-abcde           20 minutes ago    7m45s       Succeeded
```

### Step 7.2: View task-level details

List all task runs:

```bash
tkn taskrun list
```

**Expected output:**
```
NAME                                                    STARTED           DURATION    STATUS
upi-cicd-pipeline-run-xyz123-deploy-to-cloudrun-xxxxx   3 minutes ago     1m15s       Succeeded
upi-cicd-pipeline-run-xyz123-build-image-xxxxx          5 minutes ago     2m30s       Succeeded
upi-cicd-pipeline-run-xyz123-build-and-test-xxxxx       8 minutes ago     2m45s       Succeeded
upi-cicd-pipeline-run-xyz123-clone-repo-xxxxx           9 minutes ago     15s         Succeeded
upi-cicd-pipeline-run-abcde-deploy-to-cloudrun-xxxxx    18 minutes ago    1m20s       Succeeded
upi-cicd-pipeline-run-abcde-build-image-xxxxx           20 minutes ago    2m15s       Succeeded
upi-cicd-pipeline-run-abcde-build-and-test-xxxxx        23 minutes ago    2m50s       Succeeded
upi-cicd-pipeline-run-abcde-clone-repo-xxxxx            24 minutes ago    14s         Succeeded
```

View logs for a specific task run:

```bash
tkn taskrun logs upi-cicd-pipeline-run-xyz123-build-and-test-xxxxx
```

This shows the complete Maven build output including test results.

### Step 7.3: View pipeline run timing

```bash
tkn pipelinerun describe upi-cicd-pipeline-run-xyz123
```

**Expected output (final state):**
```
Name:        upi-cicd-pipeline-run-xyz123
Namespace:   default
Pipeline:    upi-cicd-pipeline

Status
STARTED          DURATION   STATUS
10 minutes ago   8m30s      Succeeded

Params
 NAME           VALUE
 repo-url       https://github.com/<your-username>/upi-transfer-service.git
 revision       main
 image-name     us-central1-docker.pkg.dev/upi-workshop-492606/upi-repo/upi-transfer-service:20260410145000
 service-name   upi-transfer-service
 project-id     upi-workshop-492606
 region         us-central1

Taskruns
 NAME                                                    TASK NAME            STARTED          DURATION   STATUS
 upi-cicd-pipeline-run-xyz123-clone-repo-xxxxx           clone-repo           10 minutes ago   15s        Succeeded
 upi-cicd-pipeline-run-xyz123-build-and-test-xxxxx       build-and-test       9 minutes ago    2m45s      Succeeded
 upi-cicd-pipeline-run-xyz123-build-image-xxxxx          build-image          7 minutes ago    2m30s      Succeeded
 upi-cicd-pipeline-run-xyz123-deploy-to-cloudrun-xxxxx   deploy-to-cloudrun   4 minutes ago    1m15s      Succeeded
```

> **Observation:** The total pipeline duration is around 7-10 minutes. The Maven build and Kaniko image build are the longest steps. In production, you would add Maven dependency caching and Kaniko layer caching to speed these up.

### Step 7.4: Debug a failed pipeline

If a pipeline run fails, here is how to diagnose it:

**Step 1: Identify which task failed:**

```bash
tkn pipelinerun describe <failed-pipelinerun-name>
```

Look for the task with `STATUS = Failed`.

**Step 2: View the failed task's logs:**

```bash
tkn taskrun logs <failed-taskrun-name>
```

**Step 3: Check pod events for infrastructure issues:**

```bash
kubectl describe pod -l tekton.dev/taskRun=<failed-taskrun-name>
```

**Common failure scenarios:**

| Problem | Cause | Solution |
|---------|-------|----------|
| `git-clone` fails with 401 | Private repo, no credentials | Add a Git secret to the workspace |
| `maven-build` fails with OOM | JVM runs out of memory | Increase memory limits in the task step |
| `maven-build` fails with test errors | Unit tests failing | Fix the tests, or temporarily set `skip-tests=true` |
| `kaniko-build` fails with 401 | Invalid registry credentials | Recreate `artifact-registry-secret` |
| `kaniko-build` fails with "Dockerfile not found" | Wrong `context` or `dockerfile` path | Check subdirectory param matches repo structure |
| `deploy-cloudrun` fails with 403 | Service account missing roles | Add `roles/run.admin` and `roles/iam.serviceAccountUser` |
| Pod stuck in `Pending` | No node has enough resources | Scale up the cluster or reduce resource requests |
| PVC stuck in `Pending` | StorageClass not available | Check `kubectl get sc` and use the correct class |

---

## Part 8: Cleanup (10 min)

### Step 8.1: Delete pipeline runs

```bash
# Delete all pipeline runs
kubectl delete pipelinerun --all
```

**Expected output:**
```
pipelinerun.tekton.dev "upi-cicd-pipeline-run-abcde" deleted
pipelinerun.tekton.dev "upi-cicd-pipeline-run-xyz123" deleted
```

### Step 8.2: Delete the pipeline

```bash
kubectl delete pipeline upi-cicd-pipeline
```

**Expected output:**
```
pipeline.tekton.dev "upi-cicd-pipeline" deleted
```

### Step 8.3: Delete all tasks

```bash
kubectl delete task --all
```

**Expected output:**
```
task.tekton.dev "deploy-cloudrun" deleted
task.tekton.dev "git-clone" deleted
task.tekton.dev "kaniko-build" deleted
task.tekton.dev "maven-build" deleted
```

### Step 8.4: Delete workspace PVC and secrets

```bash
kubectl delete pvc tekton-workspace-pvc
kubectl delete secret gcp-credentials
kubectl delete secret artifact-registry-secret
```

**Expected output:**
```
persistentvolumeclaim "tekton-workspace-pvc" deleted
secret "gcp-credentials" deleted
secret "artifact-registry-secret" deleted
```

### Step 8.5: Uninstall Tekton

```bash
# Delete Dashboard first (it depends on Pipelines)
kubectl delete --filename https://storage.googleapis.com/tekton-releases/dashboard/latest/release.yaml

# Then delete Pipelines
kubectl delete --filename https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml
```

**Expected output:**
```
deployment.apps "tekton-dashboard" deleted
service "tekton-dashboard" deleted
...
namespace "tekton-pipelines" deleted
```

### Step 8.6: Clean up GCP resources

```bash
# Delete the Cloud Run service
gcloud run services delete upi-transfer-service \
  --region=us-central1 --quiet

# Delete Artifact Registry images (keep the repo if used by other labs)
gcloud artifacts docker images delete \
  ${REGISTRY}/upi-transfer-service \
  --delete-tags --quiet

# Delete the service account
gcloud iam service-accounts delete \
  tekton-sa@${PROJECT_ID}.iam.gserviceaccount.com --quiet

# Delete the GKE cluster (only if you created it in Step 1.3)
gcloud container clusters delete $CLUSTER_NAME --zone $ZONE --quiet
```

**Expected output (for each command):**
```
Deleted service [upi-transfer-service].
Deleted image [us-central1-docker.pkg.dev/upi-workshop-492606/upi-repo/upi-transfer-service].
Deleted service account [tekton-sa@upi-workshop-492606.iam.gserviceaccount.com].
Deleting cluster tekton-cicd-cluster...done.
```

### Step 8.7: Delete local Tekton YAML files

```bash
rm -rf tekton/
```

---

## Wrap-up

### What we built

```
                    Tekton Pipeline on GKE
┌──────────────────────────────────────────────────────────┐
│                                                          │
│   ┌─────────┐   ┌────────────┐   ┌──────────┐   ┌────────────┐
│   │  Clone   │──>│   Maven    │──>│  Kaniko  │──>│  Deploy    │
│   │  Repo    │   │  Build &   │   │  Build & │   │  to Cloud  │
│   │(git)     │   │  Test      │   │  Push    │   │  Run       │
│   └─────────┘   └────────────┘   └──────────┘   └────────────┘
│       │               │               │               │      │
│   alpine/git    maven:3.9       kaniko:latest   cloud-sdk    │
│                 temurin-17                                    │
└──────────────────────────────────────────────────────────┘
       ↑                                        │
  Git Repo                                      ↓
  (GitHub)                              Cloud Run Service
                                     (upi-transfer-service)
                                        Port 8080
```

### What we proved

| Concept | How we proved it |
|---------|-----------------|
| Automated Build | Maven compiled and packaged the Spring Boot app inside a Kubernetes pod |
| Automated Test | Unit tests ran as part of the `maven-build` task (`-DskipTests=false`) |
| Container Build | Kaniko built a Docker image from the multi-stage Dockerfile without a Docker daemon |
| Image Registry | Pushed the image to Google Artifact Registry and verified it |
| Automated Deploy | `gcloud run deploy` executed inside a Tekton task, deploying to Cloud Run |
| Health Verification | Pipeline verified the deployment via `/actuator/health` endpoint |
| Observability | Monitored pipeline progress via `tkn` CLI and Tekton Dashboard |
| Repeatability | Ran the pipeline twice with different code versions and verified both deployments |

### Key Tekton concepts

| Concept | Definition | In this lab |
|---------|-----------|-------------|
| **Task** | A reusable unit of work composed of steps | `git-clone`, `maven-build`, `kaniko-build`, `deploy-cloudrun` |
| **Step** | A single container execution within a task | `git clone .`, `mvn clean package`, Kaniko executor |
| **Pipeline** | An ordered graph of tasks | `upi-cicd-pipeline` with 4 tasks |
| **PipelineRun** | A single execution of a pipeline with specific params | `upi-cicd-pipeline-run-abcde` |
| **TaskRun** | A single execution of a task (created automatically by PipelineRun) | One per task per run |
| **Workspace** | A shared volume passed between tasks | `tekton-workspace-pvc` (2Gi PVC) |
| **Param** | An input value to a task or pipeline | `repo-url`, `image-name`, etc. |
| **Result** | An output value from a task (can be passed to other tasks) | `commit-sha` from git-clone |
| **runAfter** | Declares task ordering within a pipeline | `build-and-test` runs after `clone-repo` |

### Key commands reference

| Command | Description |
|---------|-------------|
| `tkn task list` | List all tasks in the current namespace |
| `tkn task describe <name>` | Show details of a task (params, steps, workspaces) |
| `tkn pipeline list` | List all pipelines |
| `tkn pipeline describe <name>` | Show pipeline details including task ordering |
| `tkn pipeline start <name> --param key=val --showlog` | Start a pipeline run and follow logs |
| `tkn pipelinerun list` | List all pipeline runs with status |
| `tkn pipelinerun describe <name>` | Show detailed status of a pipeline run |
| `tkn pipelinerun logs <name> -f` | Follow logs of a running pipeline |
| `tkn taskrun list` | List all task runs |
| `tkn taskrun logs <name>` | View logs of a specific task run |
| `tkn pipelinerun delete --all` | Delete all pipeline runs |

### Common troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| `tkn version` shows "Pipeline version: unknown" | Tekton not installed or kubectl not connected | Run `kubectl get pods -n tekton-pipelines` to check |
| PipelineRun stays `Pending` | PVC not bound or insufficient cluster resources | Check `kubectl get pvc` and `kubectl describe pod` |
| Maven build downloads dependencies every time | No Maven cache between runs | Add a separate PVC for `~/.m2/repository` |
| Kaniko build is slow | No layer caching | Use `--cache=true` (already in our task) and a cache repo |
| Cloud Run deploy fails with "permission denied" | Service account missing IAM roles | Verify all 3 roles: `artifactregistry.writer`, `run.admin`, `iam.serviceAccountUser` |
| Dashboard not accessible | Port-forward process died | Re-run `kubectl port-forward -n tekton-pipelines svc/tekton-dashboard 9097:9097` |
| "no such host" when cloning repo | Cluster DNS issue or typo in URL | Verify the repo URL is correct and accessible |
| Task pod `OOMKilled` | Container hit memory limit | Increase `resources.limits.memory` in the task step |

---

## Bonus: What is next?

After completing this lab, consider exploring:

1. **Tekton Triggers** -- Automatically start pipelines on `git push` using webhooks
2. **Tekton Catalog** -- Reuse community tasks from [hub.tekton.dev](https://hub.tekton.dev)
3. **Workload Identity** -- Replace service account keys with GKE Workload Identity (more secure)
4. **Multi-environment pipelines** -- Add staging and production deployment stages
5. **Tekton Chains** -- Add supply chain security with signed provenance

---

*Lab 15 complete. You have built a full CI/CD pipeline on Kubernetes using Tekton.*
