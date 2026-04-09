# Lab 14: GKE — Deploy All UPI Microservices to Kubernetes

## Objectives
- Create a GKE cluster
- Build and push container images to Artifact Registry
- Deploy all 4 services with Kubernetes manifests
- Expose the UPI API via Ingress (GCP Load Balancer)
- Scale, update, and observe your microservices platform

---

## Prerequisites
- GCP project with billing enabled
- `gcloud` and `kubectl` CLI tools
- Docker Desktop running
- Pub/Sub topic and subscriptions created (from Pub/Sub lab)

---

## Part 1: Enable APIs & Set Variables

```bash
export PROJECT_ID=$(gcloud config get-value project)
export REGION=us-central1
export REPO_NAME=upi-repo
export REGISTRY=$REGION-docker.pkg.dev/$PROJECT_ID/$REPO_NAME

gcloud services enable \
  container.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com \
  pubsub.googleapis.com
```

---

## Part 2: Create Artifact Registry

Artifact Registry stores your Docker images (replaces the old Container Registry).

```bash
gcloud artifacts repositories create $REPO_NAME \
  --repository-format=docker \
  --location=$REGION

# Configure Docker to authenticate with Artifact Registry
gcloud auth configure-docker $REGION-docker.pkg.dev --quiet
```

---

## Part 3: Build & Push All Images

### Build each service

```bash
# UPI Transfer Service
cd upi-transfer-service
docker build -t $REGISTRY/upi-transfer-service:latest .
docker push $REGISTRY/upi-transfer-service:latest
cd ..

# Notification Service
cd notification-service
docker build -t $REGISTRY/notification-service:latest .
docker push $REGISTRY/notification-service:latest
cd ..

# Fraud Detection Service
cd fraud-detection-service
docker build -t $REGISTRY/fraud-detection-service:latest .
docker push $REGISTRY/fraud-detection-service:latest
cd ..

# Analytics Service
cd analytics-service
docker build -t $REGISTRY/analytics-service:latest .
docker push $REGISTRY/analytics-service:latest
cd ..
```

### Verify images

```bash
gcloud artifacts docker images list $REGISTRY
```

You should see 4 images listed.

---

## Part 4: Create GKE Cluster

```bash
gcloud container clusters create upi-cluster \
  --region=$REGION \
  --num-nodes=1 \
  --machine-type=e2-medium \
  --disk-size=30 \
  --enable-ip-alias \
  --workload-pool=$PROJECT_ID.svc.id.goog
```

> **This takes 5-10 minutes.** The cluster creates 3 nodes (1 per zone in the region).

### Get credentials

```bash
gcloud container clusters get-credentials upi-cluster \
  --region=$REGION

# Verify
kubectl cluster-info
kubectl get nodes
```

---

## Part 5: Update ConfigMap with Your Project ID

```bash
cd k8s

# Edit configmap.yaml — replace REPLACE_WITH_PROJECT_ID
sed -i "s/REPLACE_WITH_PROJECT_ID/$PROJECT_ID/g" configmap.yaml
```

Or manually edit `k8s/configmap.yaml` and set your project ID.

---

## Part 6: Deploy All Services

### Apply manifests in order

```bash
# 1. Namespace first
kubectl apply -f namespace.yaml

# 2. Config
kubectl apply -f configmap.yaml

# 3. All services (replace image placeholders)
for f in upi-transfer-service.yaml notification-service.yaml fraud-detection-service.yaml analytics-service.yaml; do
  sed "s|REGION-docker.pkg.dev/PROJECT_ID/upi-repo|$REGISTRY|g" "$f" | kubectl apply -f -
done

# 4. Ingress
kubectl apply -f ingress.yaml
```

### Watch pods come up

```bash
kubectl get pods -n upi-system -w
```

Wait until all pods show `Running` and `1/1` ready:

```
NAME                                        READY   STATUS    RESTARTS
upi-transfer-service-xxxx-yyy               1/1     Running   0
upi-transfer-service-xxxx-zzz               1/1     Running   0
notification-service-xxxx-yyy               1/1     Running   0
fraud-detection-service-xxxx-yyy            1/1     Running   0
analytics-service-xxxx-yyy                  1/1     Running   0
```

---

## Part 7: Access the UPI API

### Get the Ingress IP

```bash
kubectl get ingress -n upi-system
```

> **Ingress takes 3-5 minutes** to provision the GCP Load Balancer. Wait until you see an IP address under `ADDRESS`.

```bash
# Watch for the IP
kubectl get ingress -n upi-system --watch
```

### Test the API

```bash
export INGRESS_IP=$(kubectl get ingress upi-ingress -n upi-system -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

# Check balance
curl -s http://$INGRESS_IP/api/balance/nag@upi | jq

# Make a transfer
curl -s -X POST http://$INGRESS_IP/api/transfer \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":500,"remark":"gke-test"}' | jq

# Check transactions
curl -s http://$INGRESS_IP/api/transactions/nag@upi | jq
```

---

## Part 8: Verify Pub/Sub Flow

After making transfers, check that all 3 subscribers received the events.

### Check logs for each subscriber

```bash
# Notification service logs
kubectl logs -l app=notification-service -n upi-system --tail=20

# Fraud detection logs
kubectl logs -l app=fraud-detection-service -n upi-system --tail=20

# Analytics service logs
kubectl logs -l app=analytics-service -n upi-system --tail=20
```

### Port-forward to check stats

```bash
# Analytics stats
kubectl port-forward svc/analytics-service 8083:80 -n upi-system &
curl -s http://localhost:8083/stats | jq

# Fraud detection stats
kubectl port-forward svc/fraud-detection-service 8082:80 -n upi-system &
curl -s http://localhost:8082/stats | jq

# Notification stats
kubectl port-forward svc/notification-service 8081:80 -n upi-system &
curl -s http://localhost:8081/stats | jq

# Kill port-forwards when done
kill %1 %2 %3 2>/dev/null
```

---

## Part 9: Scaling

### Manual scaling

```bash
# Scale UPI service to 3 replicas
kubectl scale deployment upi-transfer-service \
  -n upi-system --replicas=3

# Watch new pod come up
kubectl get pods -n upi-system -l app=upi-transfer-service

# Scale back down
kubectl scale deployment upi-transfer-service \
  -n upi-system --replicas=2
```

### Horizontal Pod Autoscaler (HPA)

```bash
# Auto-scale based on CPU usage
kubectl autoscale deployment upi-transfer-service \
  -n upi-system --min=2 --max=10 --cpu-percent=70

# Check HPA status
kubectl get hpa -n upi-system
```

---

## Part 10: Rolling Update

### Simulate a code change and redeploy

```bash
# Rebuild with a tag
docker build -t $REGISTRY/upi-transfer-service:v2 upi-transfer-service/
docker push $REGISTRY/upi-transfer-service:v2

# Update the deployment image
kubectl set image deployment/upi-transfer-service \
  upi-transfer-service=$REGISTRY/upi-transfer-service:v2 \
  -n upi-system

# Watch the rolling update
kubectl rollout status deployment/upi-transfer-service -n upi-system
```

### Rollback if needed

```bash
# Undo the last update
kubectl rollout undo deployment/upi-transfer-service -n upi-system

# Check rollout history
kubectl rollout history deployment/upi-transfer-service -n upi-system
```

---

## Part 11: Observability

### Pod resource usage

```bash
kubectl top pods -n upi-system
kubectl top nodes
```

### Describe a pod (events, errors)

```bash
kubectl describe pod -l app=upi-transfer-service -n upi-system
```

### View all resources in namespace

```bash
kubectl get all -n upi-system
```

### GKE Console

1. Go to **console.cloud.google.com/kubernetes**
2. Click **upi-cluster**
3. Go to **Workloads** — see all deployments
4. Go to **Services & Ingress** — see endpoints
5. Click any workload — see pods, logs, events, CPU/memory graphs

---

## Part 12: Load Test

Fire a burst of transfers and watch the system:

```bash
# Terminal 1: Watch pods
kubectl get pods -n upi-system -w

# Terminal 2: Fire 20 transfers
for i in $(seq 1 20); do
  curl -s -X POST http://$INGRESS_IP/api/transfer \
    -H "Content-Type: application/json" \
    -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":10,"remark":"load-test-'$i'"}' &
done
wait
echo "Done!"

# Check stats
kubectl port-forward svc/analytics-service 8083:80 -n upi-system &
curl -s http://localhost:8083/stats | jq '.messagesProcessed, .throughputPerSecond'
kill %1 2>/dev/null
```

---

## Cleanup

```bash
# Delete all K8s resources
kubectl delete namespace upi-system

# Delete the GKE cluster (IMPORTANT — stops billing)
gcloud container clusters delete upi-cluster \
  --region=$REGION --quiet

# Delete images from Artifact Registry
for svc in upi-transfer-service notification-service fraud-detection-service analytics-service; do
  gcloud artifacts docker images delete $REGISTRY/$svc --quiet 2>/dev/null
done

# Delete Artifact Registry repo
gcloud artifacts repositories delete $REPO_NAME \
  --location=$REGION --quiet
```

---

## Architecture Recap

```
                    Internet
                       |
                  GCP Load Balancer
                    (Ingress)
                       |
              ┌── GKE Cluster (upi-cluster) ──┐
              │                                 │
              │  upi-transfer-service (x2)      │
              │        |                        │
              │   Pub/Sub Topic                 │
              │   /      |       \              │
              │  notification  fraud  analytics │
              │  service(x1)  svc(x1) svc(x1)  │
              └─────────────────────────────────┘
```

---

## Review Questions

1. **What is the difference between a Deployment and a Pod?** Why don't we create pods directly?
2. **What does the readiness probe do?** What happens if it fails?
3. **Why is upi-transfer-service the only one with an Ingress?** How do subscriber services get traffic?
4. **What happens when you run `kubectl scale --replicas=0`?** Is that useful?
5. **How does a rolling update work?** Why is zero-downtime possible?
6. **GKE vs Cloud Run** — which would you choose for a single stateless API? Why?

---

## What We've Built

Over these labs, we've built a complete cloud-native UPI platform:

| Layer | Service | GCP Product |
|-------|---------|-------------|
| API | upi-transfer-service | GKE (Kubernetes) |
| Messaging | Transaction events | Pub/Sub |
| Caching | Account balance cache | Memorystore (Redis) |
| Analytics | Transaction warehouse | BigQuery |
| Notifications | SMS alerts | GKE (Kubernetes) |
| Fraud | Rule engine | GKE (Kubernetes) |
| Orchestration | All services | GKE |
