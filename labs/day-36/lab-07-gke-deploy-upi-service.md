# Lab 07: Deploy UPI Transfer Service to GKE

**Duration:** 60 minutes
**Objective:** Deploy the UPI Transfer Service (Spring Boot 3.4.4, Java 17) to a GKE Autopilot cluster with Kubernetes Deployments, Services, ConfigMaps, Secrets, scaling, rolling updates, and observability.

---

## Architecture

```
Developer → Artifact Registry → GKE Autopilot Cluster
                                   ├── Namespace: upi-service
                                   ├── Deployment (3 replicas)
                                   ├── Service (LoadBalancer)
                                   ├── ConfigMap (application.yml)
                                   ├── Secret (sensitive config)
                                   └── HPA (auto-scaling)
```

---

## Part 1: Setup & Enable APIs

```bash
# ─── Set variables ───
export PROJECT_ID=$(gcloud config get-value project)
export REGION=asia-south1
export CLUSTER_NAME=upi-gke-cluster
export SERVICE_NAME=upi-transfer-service
export REPO_NAME=upi-repo
export IMAGE_TAG=v1
export IMAGE_URI=${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:${IMAGE_TAG}

echo "Project  : $PROJECT_ID"
echo "Region   : $REGION"
echo "Cluster  : $CLUSTER_NAME"
echo "Image    : $IMAGE_URI"
```

### Enable required APIs

```bash
gcloud services enable \
  container.googleapis.com \
  artifactregistry.googleapis.com \
  --project=$PROJECT_ID
```

### Verify

```bash
gcloud services list --enabled --filter="NAME:(container OR artifactregistry)" \
  --format="table(NAME, TITLE)"
```

---

## Part 2: Create GKE Autopilot Cluster

```bash
gcloud container clusters create-auto $CLUSTER_NAME \
  --region $REGION \
  --project $PROJECT_ID
```

> **Note:** Autopilot clusters are fully managed — Google handles node provisioning, scaling, and security. This command takes 5–10 minutes.

### Verify the cluster

```bash
gcloud container clusters list \
  --region $REGION \
  --format="table(NAME, LOCATION, STATUS, NUM_NODES)"
```

---

## Part 3: Connect to the Cluster

```bash
gcloud container clusters get-credentials $CLUSTER_NAME \
  --region $REGION \
  --project $PROJECT_ID
```

### Verify connection

```bash
kubectl cluster-info
```

```bash
kubectl get nodes
```

---

## Part 4: Create a Namespace

```bash
kubectl create namespace upi-service
```

### Set it as the default namespace for convenience

```bash
kubectl config set-context --current --namespace=upi-service
```

### Verify

```bash
kubectl get namespaces
```

---

## Part 5: Create Kubernetes Deployment

```bash
cat <<'EOF' > upi-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: upi-transfer-service
  namespace: upi-service
  labels:
    app: upi-transfer-service
spec:
  replicas: 2
  selector:
    matchLabels:
      app: upi-transfer-service
  template:
    metadata:
      labels:
        app: upi-transfer-service
        version: v1
    spec:
      containers:
        - name: upi-transfer-service
          image: IMAGE_PLACEHOLDER
          ports:
            - containerPort: 8080
          resources:
            requests:
              cpu: "250m"
              memory: "512Mi"
            limits:
              cpu: "500m"
              memory: "1Gi"
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 15
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "cloud"
EOF
```

### Replace the image placeholder with your actual image URI

```bash
sed -i.bak "s|IMAGE_PLACEHOLDER|${IMAGE_URI}|g" upi-deployment.yaml
cat upi-deployment.yaml
```

> **Key fields explained:**
>
> | Field | Purpose |
> |-------|---------|
> | `replicas: 2` | Run 2 pods for high availability |
> | `requests` | Minimum resources guaranteed to each pod |
> | `limits` | Maximum resources a pod can consume |
> | `readinessProbe` | Kubernetes only sends traffic after `/actuator/health` returns 200 |
> | `livenessProbe` | Kubernetes restarts the pod if health check fails |

---

## Part 6: Create Kubernetes Service (LoadBalancer)

```bash
cat <<'EOF' > upi-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: upi-transfer-service
  namespace: upi-service
  labels:
    app: upi-transfer-service
spec:
  type: LoadBalancer
  selector:
    app: upi-transfer-service
  ports:
    - protocol: TCP
      port: 80
      targetPort: 8080
      name: http
EOF
```

> The LoadBalancer Service provisions a Google Cloud external IP. Port 80 on the load balancer forwards to port 8080 in the container.

---

## Part 7: Apply and Verify

### Apply the manifests

```bash
kubectl apply -f upi-deployment.yaml
kubectl apply -f upi-service.yaml
```

### Watch pods come up

```bash
kubectl get pods -w
```

> Press Ctrl+C once all pods show `Running` and `READY 1/1`.

### Check the deployment

```bash
kubectl get deployment upi-transfer-service
```

### Check the service and wait for external IP

```bash
kubectl get svc upi-transfer-service
```

> The `EXTERNAL-IP` may show `<pending>` for 1–2 minutes while GCP provisions the load balancer. Re-run until you see an IP.

### Save the external IP

```bash
export EXTERNAL_IP=$(kubectl get svc upi-transfer-service \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "External IP: $EXTERNAL_IP"
```

---

## Part 8: Test the Live Service

### Health check

```bash
curl -s http://$EXTERNAL_IP/actuator/health | python3 -m json.tool
```

Expected:
```json
{
    "status": "UP"
}
```

### Check balance

```bash
curl -s http://$EXTERNAL_IP/api/balance/nag@upi | python3 -m json.tool
```

### Make a transfer

```bash
curl -s -X POST http://$EXTERNAL_IP/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "priya@upi",
    "amount": 500,
    "remark": "GKE deployment test"
  }' | python3 -m json.tool
```

### View transactions

```bash
curl -s http://$EXTERNAL_IP/api/transactions/nag@upi | python3 -m json.tool
```

---

## Part 9: Scaling

### Manual scaling — scale to 4 replicas

```bash
kubectl scale deployment upi-transfer-service --replicas=4
```

### Verify

```bash
kubectl get pods -l app=upi-transfer-service
```

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment upi-transfer-service \
  --min=2 \
  --max=8 \
  --cpu-percent=50
```

> This creates an HPA that scales between 2 and 8 replicas based on CPU utilization (target 50%).

### Check HPA status

```bash
kubectl get hpa upi-transfer-service
```

### Describe HPA for details

```bash
kubectl describe hpa upi-transfer-service
```

### Scale back down manually (remove HPA first)

```bash
kubectl delete hpa upi-transfer-service
kubectl scale deployment upi-transfer-service --replicas=2
```

---

## Part 10: Rolling Update

### Deploy a new version (v2)

```bash
kubectl set image deployment/upi-transfer-service \
  upi-transfer-service=${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:v2
```

### Watch the rollout progress

```bash
kubectl rollout status deployment/upi-transfer-service
```

### View rollout history

```bash
kubectl rollout history deployment/upi-transfer-service
```

### Rollback to the previous version

```bash
kubectl rollout undo deployment/upi-transfer-service
```

### Verify the rollback

```bash
kubectl rollout status deployment/upi-transfer-service
```

```bash
kubectl describe deployment upi-transfer-service | grep Image
```

> The image should show `:v1` again after the rollback.

---

## Part 11: ConfigMap for Spring Boot Properties

### Create a ConfigMap with application properties

```bash
cat <<'EOF' > upi-configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: upi-app-config
  namespace: upi-service
data:
  application.yml: |
    server:
      port: 8080
    spring:
      application:
        name: upi-transfer-service
      datasource:
        url: jdbc:h2:mem:upidb
        driver-class-name: org.h2.Driver
      h2:
        console:
          enabled: true
      jpa:
        hibernate:
          ddl-auto: update
        show-sql: false
    management:
      endpoints:
        web:
          exposure:
            include: health,info,metrics
EOF
```

### Apply the ConfigMap

```bash
kubectl apply -f upi-configmap.yaml
```

### Verify

```bash
kubectl get configmap upi-app-config
kubectl describe configmap upi-app-config
```

### Update the Deployment to mount the ConfigMap

```bash
cat <<'EOF' > upi-deployment-with-config.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: upi-transfer-service
  namespace: upi-service
  labels:
    app: upi-transfer-service
spec:
  replicas: 2
  selector:
    matchLabels:
      app: upi-transfer-service
  template:
    metadata:
      labels:
        app: upi-transfer-service
        version: v1
    spec:
      containers:
        - name: upi-transfer-service
          image: IMAGE_PLACEHOLDER
          ports:
            - containerPort: 8080
          resources:
            requests:
              cpu: "250m"
              memory: "512Mi"
            limits:
              cpu: "500m"
              memory: "1Gi"
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 15
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "cloud"
            - name: SPRING_CONFIG_ADDITIONAL_LOCATION
              value: "/config/"
          volumeMounts:
            - name: app-config
              mountPath: /config
              readOnly: true
      volumes:
        - name: app-config
          configMap:
            name: upi-app-config
EOF
```

```bash
sed -i.bak "s|IMAGE_PLACEHOLDER|${IMAGE_URI}|g" upi-deployment-with-config.yaml
kubectl apply -f upi-deployment-with-config.yaml
```

### Verify the pods restart with the new config

```bash
kubectl get pods -w
```

---

## Part 12: Secrets for Sensitive Config

### Create a Secret (e.g., for a database password)

```bash
kubectl create secret generic upi-db-secret \
  --from-literal=DB_USERNAME=upi_admin \
  --from-literal=DB_PASSWORD=S3cureP@ss2024 \
  --namespace=upi-service
```

### Verify the Secret (values are base64-encoded)

```bash
kubectl get secret upi-db-secret
kubectl describe secret upi-db-secret
```

### Create a Secret from a YAML manifest

```bash
cat <<'EOF' > upi-secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: upi-api-secret
  namespace: upi-service
type: Opaque
stringData:
  API_KEY: "my-secret-api-key-12345"
  JWT_SECRET: "super-secret-jwt-token"
EOF
```

```bash
kubectl apply -f upi-secret.yaml
```

### Reference Secrets as environment variables in a pod

> To use these secrets in your Deployment, add the following to the container's `env` section:
>
> ```yaml
> env:
>   - name: DB_USERNAME
>     valueFrom:
>       secretKeyRef:
>         name: upi-db-secret
>         key: DB_USERNAME
>   - name: DB_PASSWORD
>     valueFrom:
>       secretKeyRef:
>         name: upi-db-secret
>         key: DB_PASSWORD
> ```

---

## Part 13: View Logs

### View logs from all pods with the app label

```bash
kubectl logs -l app=upi-transfer-service --tail=50
```

### View logs from a specific pod

```bash
# Get pod name
kubectl get pods -l app=upi-transfer-service -o name

# View logs (replace with your pod name)
kubectl logs $(kubectl get pods -l app=upi-transfer-service -o jsonpath='{.items[0].metadata.name}') --tail=100
```

### Stream logs in real-time

```bash
kubectl logs -l app=upi-transfer-service -f --tail=20
```

> Press Ctrl+C to stop streaming.

### View logs from a previous container (if pod restarted)

```bash
kubectl logs $(kubectl get pods -l app=upi-transfer-service -o jsonpath='{.items[0].metadata.name}') --previous
```

---

## Part 14: Monitoring

### Check pod resource usage

```bash
kubectl top pods -l app=upi-transfer-service
```

> **Note:** `kubectl top` requires the Metrics Server, which is enabled by default on GKE Autopilot.

### Check node resource usage

```bash
kubectl top nodes
```

### Describe a pod (events, status, conditions)

```bash
kubectl describe pod $(kubectl get pods -l app=upi-transfer-service -o jsonpath='{.items[0].metadata.name}')
```

> Look at the **Events** section at the bottom — it shows scheduling, pulling images, starting containers, and probe results.

### Get detailed pod status

```bash
kubectl get pods -l app=upi-transfer-service -o wide
```

### Check deployment events

```bash
kubectl describe deployment upi-transfer-service
```

---

## Part 15: Full Cleanup

```bash
# Delete all resources in the namespace
kubectl delete namespace upi-service

# Verify namespace is gone
kubectl get namespaces
```

### Delete the GKE cluster

```bash
gcloud container clusters delete $CLUSTER_NAME \
  --region $REGION \
  --quiet
```

> Cluster deletion takes 3–5 minutes.

### Clean up local YAML files

```bash
rm -f upi-deployment.yaml upi-deployment.yaml.bak \
  upi-service.yaml upi-configmap.yaml \
  upi-deployment-with-config.yaml upi-deployment-with-config.yaml.bak \
  upi-secret.yaml
```

### Verify

```bash
gcloud container clusters list --region $REGION

echo "All GKE resources cleaned up!"
```

---

## Summary — Commands Cheat Sheet

| Action | Command |
|--------|---------|
| Create cluster | `gcloud container clusters create-auto CLUSTER --region REGION` |
| Get credentials | `gcloud container clusters get-credentials CLUSTER --region REGION` |
| Create namespace | `kubectl create namespace NAME` |
| Apply manifest | `kubectl apply -f FILE.yaml` |
| Get pods | `kubectl get pods -l app=NAME` |
| Get services | `kubectl get svc` |
| Scale replicas | `kubectl scale deployment NAME --replicas=N` |
| Autoscale (HPA) | `kubectl autoscale deployment NAME --min=2 --max=8 --cpu-percent=50` |
| Rolling update | `kubectl set image deployment/NAME CONTAINER=IMAGE:TAG` |
| Rollout status | `kubectl rollout status deployment/NAME` |
| Rollback | `kubectl rollout undo deployment/NAME` |
| Create ConfigMap | `kubectl create configmap NAME --from-file=FILE` |
| Create Secret | `kubectl create secret generic NAME --from-literal=KEY=VAL` |
| View logs | `kubectl logs -l app=NAME --tail=50` |
| Stream logs | `kubectl logs -l app=NAME -f` |
| Pod metrics | `kubectl top pods` |
| Describe pod | `kubectl describe pod POD_NAME` |
| Delete namespace | `kubectl delete namespace NAME` |
| Delete cluster | `gcloud container clusters delete CLUSTER --region REGION` |

---

## Checkpoint

- [ ] Enabled GKE and Artifact Registry APIs
- [ ] Created GKE Autopilot cluster in asia-south1
- [ ] Connected to the cluster with `get-credentials`
- [ ] Created `upi-service` namespace
- [ ] Deployed UPI Transfer Service (2 replicas)
- [ ] Created LoadBalancer Service and got external IP
- [ ] Tested all endpoints (health, balance, transfer, transactions)
- [ ] Scaled manually to 4 replicas
- [ ] Created HPA for auto-scaling
- [ ] Performed rolling update to v2
- [ ] Rolled back to v1 with `rollout undo`
- [ ] Created ConfigMap with Spring Boot application.yml
- [ ] Created Secrets for sensitive configuration
- [ ] Viewed and streamed pod logs
- [ ] Monitored pods with `kubectl top` and `kubectl describe`
- [ ] Cleaned up all resources and deleted the cluster
