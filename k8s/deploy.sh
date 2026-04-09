#!/bin/bash
# ═══════════════════════════════════════════════════
# Deploy all UPI services to GKE
# Usage: ./deploy.sh <project-id> <region>
# ═══════════════════════════════════════════════════

set -e

PROJECT_ID=${1:?Usage: ./deploy.sh <project-id> <region>}
REGION=${2:-us-central1}
CLUSTER_NAME="upi-cluster"
REPO_NAME="upi-repo"

echo "═══════════════════════════════════════"
echo "  Deploying UPI Platform to GKE"
echo "  Project: $PROJECT_ID"
echo "  Region:  $REGION"
echo "═══════════════════════════════════════"

# ── Step 1: Enable APIs ──
echo ""
echo ">> Enabling APIs..."
gcloud services enable \
  container.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com \
  pubsub.googleapis.com \
  --project=$PROJECT_ID

# ── Step 2: Create Artifact Registry repo ──
echo ""
echo ">> Creating Artifact Registry repo..."
gcloud artifacts repositories create $REPO_NAME \
  --repository-format=docker \
  --location=$REGION \
  --project=$PROJECT_ID 2>/dev/null || echo "   (repo already exists)"

# ── Step 3: Create GKE cluster ──
echo ""
echo ">> Creating GKE cluster (this takes 5-10 min)..."
gcloud container clusters create $CLUSTER_NAME \
  --region=$REGION \
  --num-nodes=1 \
  --machine-type=e2-medium \
  --disk-size=30 \
  --enable-ip-alias \
  --workload-pool=$PROJECT_ID.svc.id.goog \
  --project=$PROJECT_ID 2>/dev/null || echo "   (cluster already exists)"

# ── Step 4: Get credentials ──
echo ""
echo ">> Getting cluster credentials..."
gcloud container clusters get-credentials $CLUSTER_NAME \
  --region=$REGION \
  --project=$PROJECT_ID

# ── Step 5: Configure Docker auth ──
echo ""
echo ">> Configuring Docker auth for Artifact Registry..."
gcloud auth configure-docker $REGION-docker.pkg.dev --quiet

# ── Step 6: Build & push all images ──
REGISTRY="$REGION-docker.pkg.dev/$PROJECT_ID/$REPO_NAME"

echo ""
echo ">> Building & pushing images..."

SERVICES=("upi-transfer-service" "notification-service" "fraud-detection-service" "analytics-service")

for svc in "${SERVICES[@]}"; do
  echo ""
  echo "   Building $svc..."
  cd "$(dirname "$0")/../$svc"
  docker build -t "$REGISTRY/$svc:latest" .
  docker push "$REGISTRY/$svc:latest"
  echo "   ✓ $svc pushed"
done

cd "$(dirname "$0")"

# ── Step 7: Update manifests with project-specific values ──
echo ""
echo ">> Applying Kubernetes manifests..."

# Replace placeholders in all YAML files
for f in namespace.yaml configmap.yaml upi-transfer-service.yaml notification-service.yaml fraud-detection-service.yaml analytics-service.yaml ingress.yaml; do
  sed "s|REGION-docker.pkg.dev/PROJECT_ID/upi-repo|$REGISTRY|g; s|REPLACE_WITH_PROJECT_ID|$PROJECT_ID|g" "$f" | kubectl apply -f -
done

# ── Step 8: Wait for rollout ──
echo ""
echo ">> Waiting for deployments..."
for svc in "${SERVICES[@]}"; do
  kubectl rollout status deployment/$svc -n upi-system --timeout=120s
done

# ── Step 9: Show status ──
echo ""
echo "═══════════════════════════════════════"
echo "  Deployment Complete!"
echo "═══════════════════════════════════════"
echo ""
kubectl get pods -n upi-system
echo ""
kubectl get services -n upi-system
echo ""
kubectl get ingress -n upi-system

echo ""
echo "To access the UPI service, wait for the Ingress IP:"
echo "  kubectl get ingress -n upi-system --watch"
echo ""
echo "Then: curl http://<INGRESS-IP>/api/balance/nag@upi"
