# CI/CD with Tekton Pipelines
## Automate Everything. Ship with Confidence.

---

## Why CI/CD Matters
**Without CI/CD, every deployment is a risk.**

| Manual Process | What Goes Wrong |
|---|---|
| Developer builds locally | "Works on my machine" — fails in production |
| Copy JAR to server via SSH | Wrong version deployed, no audit trail |
| Run tests... sometimes | Bugs slip through to production |
| Deploy on Friday at 5pm | Weekend outage, nobody remembers the steps |

### The Real Cost
- **Slow** — hours of manual work per release
- **Error-prone** — humans forget steps, make typos
- **Inconsistent** — every developer deploys differently
- **Scary** — deployments become high-stress events

**CI/CD turns deployments from risky events into boring, automated routines.**

---

## What is CI/CD?
### Continuous Integration (CI)
Every code change is **automatically built, tested, and validated**.

```
Developer pushes code
     ↓
Automated build (mvn package)
     ↓
Automated tests (unit + integration)
     ↓
Build passes → code is safe to merge
Build fails  → developer fixes immediately
```

### Continuous Delivery / Deployment (CD)
Every validated change is **automatically packaged and deployed**.

| Term | Meaning |
|---|---|
| **Continuous Delivery** | Artifact is ready to deploy at any time (manual approval to prod) |
| **Continuous Deployment** | Every passing change goes straight to production (fully automated) |

### The Full Pipeline
```
Code Push → Build → Test → Package → Push Image → Deploy
   (CI)                                    (CD)
```

**CI catches bugs early. CD ships features fast.**

---

## CI/CD Tools Landscape
| Tool | Type | Strengths | Weakness |
|---|---|---|---|
| **Jenkins** | Self-hosted | Mature, plugin ecosystem | Complex setup, high maintenance |
| **GitHub Actions** | SaaS | Tight GitHub integration | Vendor lock-in, limited customization |
| **GitLab CI** | SaaS / Self-hosted | Built into GitLab | Tied to GitLab platform |
| **Cloud Build** | GCP Managed | Zero setup on GCP | GCP-only, less flexible |
| **Tekton** | Kubernetes-native | Portable, cloud-native, extensible | Steeper learning curve |

### Why Tekton for GCP?
- **Kubernetes-native** — runs on GKE, uses K8s primitives
- **No vendor lock-in** — works on any Kubernetes cluster
- **Open source** — backed by Linux Foundation (CD Foundation)
- **Powers Google Cloud Build** under the hood
- **Reusable components** — share Tasks across teams and projects

**Tekton = the Kubernetes way to do CI/CD.**

---

## What is Tekton?
**A Kubernetes-native, open-source framework for building CI/CD pipelines.**

### How It's Different
```
Traditional CI/CD (Jenkins):
  Jenkins Server → Runs jobs on agents → Deploys

Tekton (Kubernetes-native):
  Kubernetes Cluster → Runs pipelines as Pods → Deploys
```

### Key Properties
| Property | What It Means |
|---|---|
| **Kubernetes-native** | Pipelines are K8s custom resources (CRDs) |
| **Runs as Pods** | Each step runs in a container inside a Pod |
| **Declarative** | Define pipelines in YAML, version in Git |
| **Scalable** | Kubernetes handles scheduling and scaling |
| **Reusable** | Tasks from Tekton Hub can be shared across projects |

**Think of Tekton as "Kubernetes for your CI/CD pipelines."**

---

## Tekton Core Concepts
### The Building Blocks

```
Pipeline
  ├── Task 1 (clone repo)
  │     ├── Step 1: git clone
  │     └── Step 2: list files
  ├── Task 2 (build & test)
  │     ├── Step 1: mvn compile
  │     └── Step 2: mvn test
  ├── Task 3 (build image)
  │     └── Step 1: docker build
  └── Task 4 (deploy)
        └── Step 1: gcloud run deploy
```

| Concept | What It Is | Analogy |
|---|---|---|
| **Step** | A single command in a container | One line in a script |
| **Task** | A sequence of Steps | A function |
| **Pipeline** | A sequence of Tasks | The full program |
| **PipelineRun** | An execution of a Pipeline | Running the program |
| **Workspace** | Shared storage between Tasks | A shared folder |
| **TaskRun** | An execution of a single Task | Calling one function |

**Step → Task → Pipeline → PipelineRun. That's the hierarchy.**

---

## How Tekton Works — The Full Flow

```
Developer pushes code to GitHub
          ↓
GitHub Webhook fires
          ↓
Tekton EventListener receives webhook
          ↓
TriggerTemplate creates a PipelineRun
          ↓
Pipeline executes Tasks in order:
          ↓
┌─────────────────────────────────────────────────┐
│  Task 1: Clone Repo                             │
│  (git clone upi-transfer-service)               │
│                    ↓                             │
│  Task 2: Build & Test                           │
│  (mvn clean package -DskipTests=false)          │
│                    ↓                             │
│  Task 3: Build Docker Image                     │
│  (docker build -t upi-transfer-service:v1.2)    │
│                    ↓                             │
│  Task 4: Push to Artifact Registry              │
│  (docker push asia-south1-docker.pkg.dev/...)   │
│                    ↓                             │
│  Task 5: Deploy to Cloud Run                    │
│  (gcloud run deploy upi-transfer-service)       │
└─────────────────────────────────────────────────┘
          ↓
UPI Transfer Service is live with new version
```

**Every git push triggers a fully automated pipeline. No manual steps.**

---

## Tekton Task Example — Clone a Repository

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
  workspaces:
    - name: source
      description: Where the cloned repo will be stored
  steps:
    - name: clone
      image: alpine/git:latest
      script: |
        git clone $(params.repo-url) $(workspaces.source.path)/source
        cd $(workspaces.source.path)/source
        git checkout $(params.revision)
    - name: verify
      image: alpine:latest
      script: |
        ls -la $(workspaces.source.path)/source
        echo "Clone complete. Files ready for build."
```

### What's Happening
- **params** — input values (repo URL, branch) passed at runtime
- **workspaces** — shared volume where the cloned code lives
- **steps** — two containers: one clones, one verifies
- Each step runs in its own container **inside the same Pod**

---

## Tekton Pipeline — UPI Transfer Service

```yaml
apiVersion: tekton.dev/v1beta1
kind: Pipeline
metadata:
  name: upi-transfer-pipeline
spec:
  params:
    - name: repo-url
      type: string
    - name: image-tag
      type: string
  workspaces:
    - name: shared-workspace
  tasks:
    - name: clone
      taskRef:
        name: git-clone
      params:
        - name: repo-url
          value: $(params.repo-url)
      workspaces:
        - name: source
          workspace: shared-workspace

    - name: build-and-test
      taskRef:
        name: maven-build
      runAfter: ["clone"]
      workspaces:
        - name: source
          workspace: shared-workspace

    - name: build-image
      taskRef:
        name: docker-build
      runAfter: ["build-and-test"]
      params:
        - name: image
          value: "asia-south1-docker.pkg.dev/ford-upi/upi-repo/upi-transfer-service:$(params.image-tag)"
      workspaces:
        - name: source
          workspace: shared-workspace

    - name: deploy-to-cloud-run
      taskRef:
        name: gcloud-deploy
      runAfter: ["build-image"]
      params:
        - name: service-name
          value: upi-transfer-service
        - name: image
          value: "asia-south1-docker.pkg.dev/ford-upi/upi-repo/upi-transfer-service:$(params.image-tag)"
        - name: region
          value: asia-south1
```

### Key Points
- **runAfter** — controls execution order (clone → build → image → deploy)
- **shared-workspace** — all Tasks access the same source code volume
- **params flow down** — Pipeline params are passed to individual Tasks

---

## Tekton Triggers — Automate Pipeline Execution

### The Three Components

| Component | Role |
|---|---|
| **EventListener** | Receives incoming webhooks (listens on a URL) |
| **TriggerBinding** | Extracts data from the webhook payload (repo URL, branch, commit) |
| **TriggerTemplate** | Creates a PipelineRun with the extracted data |

### How They Connect
```
GitHub Push Event
       ↓
EventListener (http://tekton.example.com:8080)
       ↓
TriggerBinding (extracts repo-url, commit-sha from JSON payload)
       ↓
TriggerTemplate (creates PipelineRun with those values)
       ↓
Pipeline runs automatically
```

### EventListener YAML
```yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: EventListener
metadata:
  name: upi-github-listener
spec:
  triggers:
    - name: github-push
      bindings:
        - ref: github-push-binding
      template:
        ref: upi-pipeline-template
```

### TriggerBinding YAML
```yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: TriggerBinding
metadata:
  name: github-push-binding
spec:
  params:
    - name: repo-url
      value: $(body.repository.clone_url)
    - name: image-tag
      value: $(body.head_commit.id)
```

**Result: Push to GitHub → Pipeline runs automatically. True CI/CD.**

---

## Cloud Build — The Managed Alternative

### Cloud Build vs Tekton
| Factor | Tekton | Cloud Build |
|---|---|---|
| **Setup** | Install on GKE, configure CRDs | Zero setup, fully managed |
| **Infrastructure** | You manage the K8s cluster | Google manages everything |
| **Portability** | Runs anywhere K8s runs | GCP only |
| **Flexibility** | Full control over execution | Opinionated, limited customization |
| **Cost** | Cluster cost + pipeline runs | Pay per build-minute |
| **Learning curve** | Steeper (K8s + Tekton concepts) | Easier (simple YAML) |

### Cloud Build Example for UPI Service
```yaml
# cloudbuild.yaml
steps:
  - name: 'maven:3.9-eclipse-temurin-17'
    args: ['mvn', 'clean', 'package', '-DskipTests=false']

  - name: 'gcr.io/cloud-builders/docker'
    args: ['build', '-t', 'asia-south1-docker.pkg.dev/ford-upi/upi-repo/upi-transfer-service:$COMMIT_SHA', '.']

  - name: 'gcr.io/cloud-builders/docker'
    args: ['push', 'asia-south1-docker.pkg.dev/ford-upi/upi-repo/upi-transfer-service:$COMMIT_SHA']

  - name: 'gcr.io/cloud-builders/gcloud'
    args: ['run', 'deploy', 'upi-transfer-service',
           '--image', 'asia-south1-docker.pkg.dev/ford-upi/upi-repo/upi-transfer-service:$COMMIT_SHA',
           '--region', 'asia-south1']
```

### When to Use Which
- **Cloud Build** — small teams, GCP-only, want minimal ops overhead
- **Tekton** — multi-cloud, need full control, want Kubernetes-native pipelines

---

## CI/CD Best Practices

| Practice | Why It Matters |
|---|---|
| **Trunk-based development** | Short-lived branches reduce merge conflicts |
| **Fast feedback loops** | Pipeline should complete in under 10 minutes |
| **Immutable artifacts** | Never modify a built image — build a new one |
| **Tag images with commit SHA** | Always know exactly what code is running |
| **Automated rollback** | If health check fails, revert to previous revision |
| **Separate build and deploy** | Build once, deploy to dev → staging → prod |

### Rollback Strategy on Cloud Run
```bash
# List revisions
gcloud run revisions list --service=upi-transfer-service --region=asia-south1

# Instant rollback to previous version
gcloud run services update-traffic upi-transfer-service \
  --to-revisions=upi-transfer-service-00003-abc=100 \
  --region=asia-south1
```

### Pipeline Speed Targets
| Stage | Target Time |
|---|---|
| Clone | < 10 seconds |
| Build + Test | < 3 minutes |
| Docker Build | < 2 minutes |
| Push Image | < 1 minute |
| Deploy to Cloud Run | < 1 minute |
| **Total Pipeline** | **< 7 minutes** |

**Fast pipelines keep developers in flow. Slow pipelines get ignored.**

---

## Expert Takeaways
- **CI/CD is not optional** — it's the foundation of modern software delivery
- **Tekton = Kubernetes-native CI/CD** — portable, extensible, open-source
- **Pipeline = Tasks + Steps** — each step runs in its own container
- **Triggers automate everything** — push to Git, pipeline runs, app deploys
- **Cloud Build is the easy path** — use it when you don't need Tekton's flexibility
- **Immutable artifacts + fast feedback** = the two most important CI/CD practices

---

## What's Next?
**Next:** Pub/Sub & Event-Driven Architecture — decouple your microservices with asynchronous messaging. How the UPI transfer service publishes transaction events for downstream consumers.
