# Lab 02: Tekton Pipeline Setup

**Duration:** 45 minutes
**Objective:** Install Tekton on a Kubernetes cluster, create Tasks and a Pipeline, and run a CI/CD pipeline for the UPI Transfer Service.

---

## Prerequisites

- Lab 01 completed (Cloud Build working, image in Artifact Registry)
- Basic understanding of Kubernetes concepts (Pods, namespaces)

---

## Part 1: Set Up a Kubernetes Cluster

We'll use a small GKE Autopilot cluster for Tekton.

```bash
# Enable GKE API
gcloud services enable container.googleapis.com

# Create a small Autopilot cluster
gcloud container clusters create-auto tekton-cluster \
  --region=asia-south1

# Get credentials
gcloud container clusters get-credentials tekton-cluster \
  --region=asia-south1

# Verify connection
kubectl cluster-info
```

> Autopilot clusters take 5-8 minutes to create. GKE manages node provisioning automatically.

---

## Part 2: Install Tekton

### Install Tekton Pipelines

```bash
kubectl apply --filename \
  https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml

# Wait for Tekton to be ready
kubectl wait --for=condition=ready pod \
  --all -n tekton-pipelines --timeout=120s
```

### Verify installation

```bash
kubectl get pods -n tekton-pipelines
```

You should see:

| Pod | Status |
|-----|--------|
| `tekton-pipelines-controller-*` | Running |
| `tekton-pipelines-webhook-*` | Running |

### Install Tekton CLI (tkn)

```bash
# In Cloud Shell, tkn may already be available. If not:
curl -LO https://github.com/tektoncd/cli/releases/latest/download/tkn_Linux_x86_64.tar.gz
tar xvzf tkn_Linux_x86_64.tar.gz -C /usr/local/bin/ tkn
tkn version
```

---

## Part 3: Create a Tekton Task — Clone Repository

Create a file `tekton/git-clone-task.yaml`:

```yaml
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: git-clone
spec:
  params:
    - name: repo-url
      type: string
      description: The Git repository URL
    - name: revision
      type: string
      default: main
      description: Branch or tag to checkout
  workspaces:
    - name: source
      description: Workspace where the repo will be cloned
  steps:
    - name: clone
      image: alpine/git:latest
      script: |
        #!/bin/sh
        echo "Cloning $(params.repo-url) @ $(params.revision)"
        git clone $(params.repo-url) $(workspaces.source.path)/source
        cd $(workspaces.source.path)/source
        git checkout $(params.revision)
        echo "--- Clone complete ---"
        ls -la
```

### Apply the Task

```bash
kubectl apply -f tekton/git-clone-task.yaml

# Verify
tkn task list
```

### Test it with a TaskRun

```bash
cat <<EOF | kubectl apply -f -
apiVersion: tekton.dev/v1beta1
kind: TaskRun
metadata:
  generateName: git-clone-run-
spec:
  taskRef:
    name: git-clone
  params:
    - name: repo-url
      value: "https://github.com/YOUR_USERNAME/upi-transfer-service.git"
  workspaces:
    - name: source
      emptyDir: {}
EOF
```

### Check the results

```bash
# List TaskRuns
tkn taskrun list

# View logs of the latest run
tkn taskrun logs --last
```

You should see the clone output with the repository file listing.

---

## Part 4: Create a Maven Build Task

Create `tekton/maven-build-task.yaml`:

```yaml
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: maven-build
spec:
  workspaces:
    - name: source
      description: Workspace with the cloned source code
  steps:
    - name: build
      image: maven:3.9-eclipse-temurin-17
      workingDir: $(workspaces.source.path)/source
      script: |
        #!/bin/bash
        echo "Building UPI Transfer Service..."
        mvn clean package -DskipTests -B
        echo "--- Build complete ---"
        ls -la target/*.jar
    - name: verify
      image: maven:3.9-eclipse-temurin-17
      workingDir: $(workspaces.source.path)/source
      script: |
        #!/bin/bash
        JAR=$(ls target/*.jar | head -1)
        echo "Built artifact: $JAR"
        echo "Size: $(du -h $JAR | cut -f1)"
```

```bash
kubectl apply -f tekton/maven-build-task.yaml
tkn task list
```

---

## Part 5: Create the Full Pipeline

Create `tekton/upi-pipeline.yaml`:

```yaml
apiVersion: tekton.dev/v1beta1
kind: Pipeline
metadata:
  name: upi-transfer-pipeline
spec:
  params:
    - name: repo-url
      type: string
      description: Git repository URL
    - name: revision
      type: string
      default: main
  workspaces:
    - name: shared-workspace
  tasks:
    - name: clone
      taskRef:
        name: git-clone
      params:
        - name: repo-url
          value: $(params.repo-url)
        - name: revision
          value: $(params.revision)
      workspaces:
        - name: source
          workspace: shared-workspace

    - name: build
      taskRef:
        name: maven-build
      runAfter:
        - clone
      workspaces:
        - name: source
          workspace: shared-workspace
```

```bash
kubectl apply -f tekton/upi-pipeline.yaml
tkn pipeline list
```

---

## Part 6: Run the Pipeline

### Create a PipelineRun

```bash
cat <<EOF | kubectl apply -f -
apiVersion: tekton.dev/v1beta1
kind: PipelineRun
metadata:
  generateName: upi-pipeline-run-
spec:
  pipelineRef:
    name: upi-transfer-pipeline
  params:
    - name: repo-url
      value: "https://github.com/YOUR_USERNAME/upi-transfer-service.git"
  workspaces:
    - name: shared-workspace
      volumeClaimTemplate:
        spec:
          accessModes:
            - ReadWriteOnce
          resources:
            requests:
              storage: 1Gi
EOF
```

### Or use the tkn CLI

```bash
tkn pipeline start upi-transfer-pipeline \
  --param repo-url=https://github.com/YOUR_USERNAME/upi-transfer-service.git \
  --workspace name=shared-workspace,volumeClaimTemplateFile=tekton/pvc.yaml \
  --showlog
```

### Watch the pipeline

```bash
# List PipelineRuns
tkn pipelinerun list

# Stream logs
tkn pipelinerun logs --last -f
```

You should see:

```
[clone : clone] Cloning https://github.com/...
[clone : clone] --- Clone complete ---
[build : build] Building UPI Transfer Service...
[build : build] [INFO] BUILD SUCCESS
[build : verify] Built artifact: target/upi-transfer-service-0.0.1-SNAPSHOT.jar
```

---

## Part 7: Inspect the Results

```bash
# Pipeline run status
tkn pipelinerun describe --last

# All Kubernetes resources created
kubectl get pipelineruns
kubectl get taskruns
kubectl get pods
```

### Understand the Pod structure

```bash
# Each TaskRun creates a Pod with one container per Step
kubectl get pods -l tekton.dev/pipeline=upi-transfer-pipeline

# View the Pod details
kubectl describe pod $(kubectl get pods -l tekton.dev/pipeline=upi-transfer-pipeline -o name | tail -1)
```

| Resource | What It Is |
|----------|-----------|
| PipelineRun | One execution of the pipeline |
| TaskRun | One execution of a task (created by PipelineRun) |
| Pod | One Kubernetes pod per TaskRun |
| Container | One container per Step inside the Pod |

---

## Checkpoint

- [ ] GKE cluster created and Tekton installed
- [ ] Created `git-clone` Task and tested with TaskRun
- [ ] Created `maven-build` Task
- [ ] Created `upi-transfer-pipeline` Pipeline chaining both Tasks
- [ ] Ran the pipeline and verified logs show successful clone + build
- [ ] Understood the Tekton resource hierarchy: Pipeline → Task → Step

---

## Key Takeaways

- **Tekton runs on Kubernetes** — pipelines are just Kubernetes resources (CRDs)
- **Tasks are reusable** — the same `git-clone` Task works for any project
- **Workspaces share data** between Tasks via persistent volumes
- **tkn CLI** makes it easy to start, monitor, and debug pipelines
- **Cloud Build is simpler**, but Tekton gives you full Kubernetes-native control
