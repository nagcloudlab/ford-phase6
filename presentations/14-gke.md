# Google Kubernetes Engine (GKE) — Container Orchestration at Scale
## Run Containers. Scale Automatically. Deploy with Confidence.

---

## The Problem — Why Containers Alone Aren't Enough
```
You built a Docker image for UPI Transfer Service. Now what?

- How do you run 10 copies across multiple machines?
- What happens when a container crashes at 3 AM?
- How do you roll out a new version with zero downtime?
- How do you route traffic to healthy containers only?
```

### What Goes Wrong Without Orchestration
- Container crashes → **nobody restarts it** → service is down
- Traffic spike → **no auto-scaling** → requests timeout
- New deployment → **stop old, start new** → downtime window
- Config changes → **rebuild image and redeploy** → slow and error-prone

**Docker gives you packaging. Kubernetes gives you operations.**

---

## Why Kubernetes? — Cloud Run vs GKE

### Cloud Run: Serverless Containers
- Deploy a container → Google handles everything else
- Scales to zero → pay nothing when idle
- Request-driven → one request per container instance (configurable)

### GKE: Managed Kubernetes
- Full control over cluster, networking, scheduling
- Long-running processes, background workers, stateful workloads
- Complex multi-service deployments with fine-grained networking

### When to Pick Which
| Requirement | Cloud Run | GKE |
|---|---|---|
| Simple HTTP APIs | **Best choice** | Overkill |
| Scale to zero | **Built-in** | Not native |
| Background workers / cron | Limited | **Full support** |
| Multi-container pods (sidecars) | Limited | **Full support** |
| Custom networking (VPNs, service mesh) | Limited | **Full control** |
| Stateful workloads (databases, caches) | Not supported | **Supported** |
| GPU / ML workloads | Limited | **Full support** |
| Team already knows Kubernetes | N/A | **Leverage existing skills** |
| Compliance requires dedicated nodes | Not possible | **Supported** |

**Start with Cloud Run. Move to GKE when you hit its limits.**

---

## What is GKE?
**Google Kubernetes Engine — a fully managed Kubernetes service on GCP.**

### What Google Manages for You
| Concern | Without GKE | With GKE |
|---|---|---|
| **Control plane** | You install, patch, and monitor etcd + API server | Google manages it — 99.95% SLA |
| **Node upgrades** | Manual OS patching, version skew risks | Auto-upgrade with maintenance windows |
| **Scaling** | Manual capacity planning | Cluster autoscaler adds/removes nodes |
| **Networking** | Configure CNI, load balancers, firewall rules | VPC-native networking out of the box |
| **Security** | Manage certs, RBAC, secrets encryption | Shielded nodes, Workload Identity, KMS |

**You focus on deploying your Spring Boot apps. Google keeps the cluster healthy.**

---

## Core Kubernetes Concepts

### Cluster
The entire Kubernetes environment — a **control plane** (brain) + **worker nodes** (muscle).
```
┌──────────────────────────────────────────┐
│              GKE Cluster                 │
│  ┌──────────────┐  ┌──────────────────┐  │
│  │ Control Plane │  │  Worker Nodes    │  │
│  │ (Google-      │  │  Node 1: Pod Pod │  │
│  │  managed)     │  │  Node 2: Pod Pod │  │
│  │               │  │  Node 3: Pod Pod │  │
│  └──────────────┘  └──────────────────┘  │
└──────────────────────────────────────────┘
```

### Node
A VM (Compute Engine instance) that runs your containers. GKE manages the pool of nodes.

### Pod
The smallest deployable unit. Usually **one container per pod**. Containers in the same pod share networking and storage.

### Deployment
Declares the **desired state** — "I want 3 replicas of UPI Transfer Service running version 2.1." Kubernetes makes it happen.

### Service
A **stable network endpoint** for a set of pods. Pods come and go; the Service IP stays the same.

### Namespace
A logical boundary inside the cluster. Use it to separate environments or teams.
```
Namespace: upi-dev      → dev pods, dev configs
Namespace: upi-staging  → staging pods, staging configs
Namespace: upi-prod     → production pods, production configs
```

### ConfigMap
External configuration injected into pods — **no need to rebuild the image** when config changes.

### Secret
Like ConfigMap but for sensitive data (passwords, API keys). Stored encrypted, mounted as files or env vars.

---

## GKE Modes: Standard vs Autopilot

| Feature | Standard | Autopilot |
|---|---|---|
| **Node management** | You choose machine types, manage node pools | Google provisions nodes automatically |
| **Pricing** | Pay per node (VM), even if pods use 10% of capacity | Pay per pod resource request |
| **Scaling** | Cluster autoscaler (you configure) | Fully automatic |
| **Customization** | Full — SSH into nodes, DaemonSets, privileged pods | Restricted — Google enforces best practices |
| **Security hardening** | Your responsibility | Built-in (no SSH, no privileged pods) |
| **Best for** | Teams that need full control | Teams that want simplicity |

### Which Mode for UPI Transfer Service?
- **Starting out / small team** → Autopilot. Less to manage, pay for what you use.
- **Complex requirements** (GPU, custom networking, DaemonSets) → Standard.

```bash
# Create an Autopilot cluster
gcloud container clusters create-auto upi-cluster \
  --region=asia-south1 \
  --project=ford-upi-project

# Create a Standard cluster
gcloud container clusters create upi-cluster \
  --region=asia-south1 \
  --num-nodes=3 \
  --machine-type=e2-medium \
  --project=ford-upi-project
```

---

## GKE Networking — Service Types

### ClusterIP (Default)
Accessible **only inside the cluster**. Other pods can reach it; external traffic cannot.
```
Pod A → ClusterIP Service → Pod B
         (10.96.0.12)
```
**Use for:** internal services like a database or cache.

### NodePort
Opens a **static port on every node**. External traffic hits `<NodeIP>:<NodePort>`.
```
External → Node1:30080 → Pod
           Node2:30080 → Pod
```
**Use for:** dev/testing. Not for production.

### LoadBalancer
Provisions a **GCP Network Load Balancer** with an external IP.
```
External → GCP Load Balancer (34.x.x.x) → Pods
```
**Use for:** exposing a single service to the internet.

### Ingress
A **single load balancer** that routes to multiple services based on URL paths or hostnames.
```
api.upi.ford.com/transfers  → Transfer Service
api.upi.ford.com/accounts   → Account Service
api.upi.ford.com/health     → Health Check Service
```
**Use for:** production. One IP, multiple services, SSL termination, path-based routing.

---

## Deploying UPI Transfer Service on GKE

### Step 1 — Deployment YAML
```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: upi-transfer-service
  namespace: upi-prod
  labels:
    app: upi-transfer-service
spec:
  replicas: 3
  selector:
    matchLabels:
      app: upi-transfer-service
  template:
    metadata:
      labels:
        app: upi-transfer-service
    spec:
      containers:
        - name: upi-transfer-service
          image: asia-south1-docker.pkg.dev/ford-upi-project/upi-repo/upi-transfer-service:2.1.0
          ports:
            - containerPort: 8080
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "gke"
          envFrom:
            - configMapRef:
                name: upi-app-config
            - secretRef:
                name: upi-db-credentials
          resources:
            requests:
              cpu: "250m"
              memory: "512Mi"
            limits:
              cpu: "1000m"
              memory: "1Gi"
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 15
```

### Step 2 — Service YAML
```yaml
# k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: upi-transfer-service
  namespace: upi-prod
spec:
  type: LoadBalancer
  selector:
    app: upi-transfer-service
  ports:
    - protocol: TCP
      port: 80
      targetPort: 8080
```

### Step 3 — Deploy
```bash
# Connect to the cluster
gcloud container clusters get-credentials upi-cluster \
  --region=asia-south1

# Create namespace
kubectl create namespace upi-prod

# Apply the manifests
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml

# Watch pods come up
kubectl get pods -n upi-prod -w

# Get the external IP
kubectl get service upi-transfer-service -n upi-prod
```

**Spring Boot + Kubernetes health probes = Kubernetes knows when your app is ready and healthy.**

---

## Scaling — HPA and Manual Scaling

### Manual Scaling
```bash
# Scale to 5 replicas
kubectl scale deployment upi-transfer-service -n upi-prod --replicas=5

# Scale down to 2
kubectl scale deployment upi-transfer-service -n upi-prod --replicas=2
```

### Horizontal Pod Autoscaler (HPA)
Automatically scales pods based on CPU, memory, or custom metrics.
```yaml
# k8s/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: upi-transfer-hpa
  namespace: upi-prod
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: upi-transfer-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

```bash
# Apply and monitor
kubectl apply -f k8s/hpa.yaml
kubectl get hpa -n upi-prod -w
```

### How HPA Works
```
CPU at 30% → 2 pods (minimum)
CPU at 75% → HPA adds pods → 4 pods
CPU at 90% → HPA adds more → 7 pods
Traffic drops → CPU at 25% → HPA scales down → 2 pods
```

**Always set resource requests on your pods. HPA needs them to calculate utilization.**

---

## Rolling Updates and Rollbacks

### Rolling Update (Default Strategy)
When you update the image tag, Kubernetes **gradually replaces** old pods with new ones.
```bash
# Update the image
kubectl set image deployment/upi-transfer-service \
  upi-transfer-service=asia-south1-docker.pkg.dev/ford-upi-project/upi-repo/upi-transfer-service:2.2.0 \
  -n upi-prod

# Watch the rollout
kubectl rollout status deployment/upi-transfer-service -n upi-prod
```

### What Happens During a Rolling Update
```
Step 1: [v2.1] [v2.1] [v2.1]           ← 3 old pods running
Step 2: [v2.1] [v2.1] [v2.1] [v2.2]    ← 1 new pod starting
Step 3: [v2.1] [v2.1] [v2.2] [v2.2]    ← new pod ready, old pod terminating
Step 4: [v2.1] [v2.2] [v2.2] [v2.2]    ← continuing
Step 5: [v2.2] [v2.2] [v2.2]           ← all new, zero downtime
```

### Rollback When Things Go Wrong
```bash
# Check rollout history
kubectl rollout history deployment/upi-transfer-service -n upi-prod

# Rollback to previous version
kubectl rollout undo deployment/upi-transfer-service -n upi-prod

# Rollback to a specific revision
kubectl rollout undo deployment/upi-transfer-service -n upi-prod --to-revision=3
```

### Rollout Strategy in YAML
```yaml
spec:
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1          # Max extra pods during update
      maxUnavailable: 0    # Never have fewer than desired count
```

**maxUnavailable: 0 + readinessProbe = true zero-downtime deployments.**

---

## ConfigMaps and Secrets for Spring Boot

### ConfigMap — Application Configuration
```yaml
# k8s/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: upi-app-config
  namespace: upi-prod
data:
  SPRING_DATASOURCE_URL: "jdbc:postgresql://10.0.0.5:5432/upi_db"
  SPRING_CLOUD_GCP_PROJECT_ID: "ford-upi-project"
  UPI_TRANSFER_DAILY_LIMIT: "100000"
  UPI_TRANSFER_PER_TRANSACTION_LIMIT: "5000"
  SERVER_PORT: "8080"
```

### Secret — Sensitive Data
```bash
# Create secret from literal values
kubectl create secret generic upi-db-credentials \
  --namespace=upi-prod \
  --from-literal=SPRING_DATASOURCE_USERNAME=upi_app \
  --from-literal=SPRING_DATASOURCE_PASSWORD=s3cureP@ss \
  --from-literal=UPI_API_KEY=ak_live_7f8g9h0j

# Or from a file
kubectl create secret generic upi-db-credentials \
  --namespace=upi-prod \
  --from-env-file=secrets.env
```

### How Spring Boot Picks Them Up
The `envFrom` in the Deployment YAML injects all keys as environment variables. Spring Boot's relaxed binding does the rest:
```
ConfigMap key:      SPRING_DATASOURCE_URL
Spring property:    spring.datasource.url
```

### Update Config Without Rebuilding the Image
```bash
# Edit the ConfigMap
kubectl edit configmap upi-app-config -n upi-prod

# Restart pods to pick up changes
kubectl rollout restart deployment/upi-transfer-service -n upi-prod
```

**ConfigMaps and Secrets separate configuration from code. Different environments, same image.**

---

## GKE + Artifact Registry Integration

### The Flow
```
Developer → mvn package → Docker build → Push to Artifact Registry → GKE pulls image
```

### Setup
```bash
# Create a Docker repository in Artifact Registry
gcloud artifacts repositories create upi-repo \
  --repository-format=docker \
  --location=asia-south1 \
  --description="UPI Transfer Service images"

# Configure Docker to authenticate with Artifact Registry
gcloud auth configure-docker asia-south1-docker.pkg.dev

# Build and push
docker build -t asia-south1-docker.pkg.dev/ford-upi-project/upi-repo/upi-transfer-service:2.1.0 .
docker push asia-south1-docker.pkg.dev/ford-upi-project/upi-repo/upi-transfer-service:2.1.0
```

### GKE Authentication to Artifact Registry
GKE nodes authenticate to Artifact Registry automatically using the node service account. No image pull secrets needed if the repository is in the same project.

For cross-project access:
```bash
# Grant the GKE node service account access to the Artifact Registry
gcloud artifacts repositories add-iam-policy-binding upi-repo \
  --location=asia-south1 \
  --member="serviceAccount:PROJECT_NUMBER-compute@developer.gserviceaccount.com" \
  --role="roles/artifactregistry.reader"
```

**Same project = automatic authentication. Cross-project = one IAM binding.**

---

## Monitoring GKE

### Cloud Monitoring (Built-in)
GKE automatically sends metrics to Cloud Monitoring:
- **Node metrics** — CPU, memory, disk usage per node
- **Pod metrics** — CPU, memory, restart count per pod
- **Container metrics** — resource usage vs limits

### kubectl Commands for Quick Checks
```bash
# Node resource usage
kubectl top nodes

# Pod resource usage
kubectl top pods -n upi-prod

# Pod logs
kubectl logs -f deployment/upi-transfer-service -n upi-prod

# Describe a pod (events, status, errors)
kubectl describe pod <pod-name> -n upi-prod

# Get all resources in the namespace
kubectl get all -n upi-prod
```

### Key Alerts to Set Up
| Alert | Condition | Why |
|---|---|---|
| **Pod CrashLoopBackOff** | Pod restarted > 5 times in 10 min | App is crashing repeatedly |
| **High CPU utilization** | Node CPU > 85% for 5 min | Cluster needs more nodes or pods |
| **HPA at max replicas** | Current replicas = max replicas | App may need higher limits |
| **Pod pending** | Pod in Pending state > 2 min | Cluster out of resources |
| **5xx error rate** | > 1% of requests returning 5xx | Application error |

### Spring Boot Actuator + Prometheus (Optional)
```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```
Expose `/actuator/prometheus` and scrape with Cloud Monitoring's managed Prometheus or a self-hosted Prometheus instance.

---

## Best Practices and Cost Optimization

### Resource Management
- **Always set resource requests and limits** — prevents noisy-neighbor problems and enables HPA
- **Right-size your pods** — start small, monitor actual usage with `kubectl top`, adjust
- **Use Autopilot** if you don't need low-level node control — you pay for pod requests, not idle node capacity

### Reliability
- **Run at least 2 replicas** in production — one pod can be evicted during node upgrades
- **Use PodDisruptionBudgets** — ensure a minimum number of pods stay running during maintenance
- **Set readiness and liveness probes** — Kubernetes only routes traffic to healthy pods
- **Use anti-affinity rules** — spread pods across nodes so one node failure doesn't take down the service

### Security
- **Use Workload Identity** — map Kubernetes service accounts to GCP service accounts (no key files)
- **Never store secrets in images or code** — use Kubernetes Secrets or Secret Manager
- **Enable Binary Authorization** — only allow trusted, signed images to run in the cluster
- **Use private clusters** — nodes have no public IPs, only accessible via VPN or IAP

### Cost Optimization
| Strategy | Savings | Trade-off |
|---|---|---|
| **Autopilot mode** | Pay per pod, not per node | Less customization |
| **Spot VMs for non-critical workloads** | 60-91% discount | Pods can be preempted |
| **HPA + Cluster Autoscaler** | Scale down when idle | Brief scale-up latency |
| **Right-size resource requests** | Avoid over-provisioning | Requires monitoring |
| **Committed use discounts** | Up to 57% for steady workloads | 1-3 year commitment |
| **Namespace resource quotas** | Prevent runaway costs | Teams must plan capacity |

---

## GKE vs Cloud Run — Comparison Table

| Dimension | Cloud Run | GKE |
|---|---|---|
| **Abstraction level** | Container-as-a-service | Cluster-as-a-service |
| **Setup time** | Minutes | 10-15 minutes |
| **Scaling** | 0 to N (per request) | N to M (pod-based, HPA) |
| **Scale to zero** | Yes | No (minimum 1 pod) |
| **Pricing** | Per request + CPU/memory per 100ms | Per node (Standard) or per pod (Autopilot) |
| **Startup latency** | Cold start possible (~1-5s) | Pods always warm |
| **Max request timeout** | 60 minutes | Unlimited |
| **Background tasks** | Limited (Cloud Run jobs) | Full support |
| **Sidecars** | Limited | Full support |
| **Networking** | Simple (HTTPS endpoint) | Full Kubernetes networking |
| **Service mesh** | Not supported | Istio / Anthos Service Mesh |
| **Stateful workloads** | Not supported | PersistentVolumes, StatefulSets |
| **GPU support** | Limited | Full support |
| **Ops overhead** | Near zero | Medium (even with Autopilot) |
| **Portability** | Cloud Run API (GCP-only) | Kubernetes API (multi-cloud) |

### Decision Guide
- **UPI Transfer Service (HTTP API, stateless)** → Cloud Run or GKE — both work
- **UPI Notification Worker (background processing)** → GKE (long-running pods)
- **UPI Analytics Pipeline (GPU, ML inference)** → GKE (GPU node pools)
- **Quick prototype / hackathon** → Cloud Run (deploy in seconds)
- **Enterprise multi-service platform** → GKE (full orchestration)

---

## Expert Takeaways
- **GKE is managed Kubernetes** — Google handles the control plane, you handle the workloads
- **Deployments + Services + HPA** is the core pattern — learn these three well and you cover 90% of use cases
- **Autopilot mode** removes node management — pay for what your pods actually request
- **Rolling updates + readiness probes** = zero-downtime deployments out of the box
- **ConfigMaps and Secrets** keep configuration out of your Docker image — same image, any environment
- **Start with Cloud Run** for simple HTTP services. Move to GKE when you need background workers, stateful workloads, or advanced networking
- **Always set resource requests, liveness probes, and at least 2 replicas** in production

---

## What's Next?
**Next:** Infrastructure as Code with Terraform — how to define your GKE clusters, Cloud SQL instances, and networking as version-controlled code instead of manual gcloud commands.
