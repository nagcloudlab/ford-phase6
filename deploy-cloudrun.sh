#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Deploy all 4 UPI Pub/Sub Microservices to Cloud Run
# Usage: ./deploy-cloudrun.sh
# ═══════════════════════════════════════════════════════════════

set -e

PROJECT_ID=$(gcloud config get-value project 2>/dev/null)
REGION=us-central1

if [ -z "$PROJECT_ID" ]; then
  echo "ERROR: No GCP project set. Run: gcloud config set project <your-project>"
  exit 1
fi

echo "═══════════════════════════════════════════════"
echo "  Deploying UPI Platform to Cloud Run"
echo "  Project: $PROJECT_ID"
echo "  Region:  $REGION"
echo "═══════════════════════════════════════════════"
echo ""

# ── Step 1: Enable APIs ──
echo ">> [1/6] Enabling APIs..."
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  pubsub.googleapis.com \
  artifactregistry.googleapis.com \
  --project=$PROJECT_ID --quiet

echo "   Done."

# ── Step 2: Create Pub/Sub topic + subscriptions ──
echo ""
echo ">> [2/6] Creating Pub/Sub resources..."
gcloud pubsub topics create transaction-events 2>/dev/null && echo "   Created topic: transaction-events" || echo "   Topic already exists: transaction-events"
gcloud pubsub topics create transaction-events-dlq 2>/dev/null && echo "   Created topic: transaction-events-dlq" || echo "   Topic already exists: transaction-events-dlq"
gcloud pubsub subscriptions create notification-sub --topic=transaction-events 2>/dev/null && echo "   Created sub: notification-sub" || echo "   Sub already exists: notification-sub"
gcloud pubsub subscriptions create fraud-detection-sub --topic=transaction-events 2>/dev/null && echo "   Created sub: fraud-detection-sub" || echo "   Sub already exists: fraud-detection-sub"
gcloud pubsub subscriptions create analytics-sub --topic=transaction-events 2>/dev/null && echo "   Created sub: analytics-sub" || echo "   Sub already exists: analytics-sub"
gcloud pubsub subscriptions create dlq-monitor-sub --topic=transaction-events-dlq 2>/dev/null && echo "   Created sub: dlq-monitor-sub" || echo "   Sub already exists: dlq-monitor-sub"

# ── Step 3: Build all images with Cloud Build ──
echo ""
echo ">> [3/6] Building container images (this takes a few minutes per service)..."

SERVICES=("upi-transfer-service" "notification-service" "fraud-detection-service" "analytics-service")
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

for svc in "${SERVICES[@]}"; do
  echo ""
  echo "   Building $svc..."
  cd "$SCRIPT_DIR/$svc"
  gcloud builds submit --tag gcr.io/$PROJECT_ID/$svc --quiet
  echo "   ✓ $svc built and pushed"
done

cd "$SCRIPT_DIR"

# ── Step 4: Deploy upi-transfer-service (publisher) ──
echo ""
echo ">> [4/6] Deploying upi-transfer-service..."
gcloud run deploy upi-transfer-service \
  --image=gcr.io/$PROJECT_ID/upi-transfer-service \
  --region=$REGION \
  --platform=managed \
  --allow-unauthenticated \
  --set-env-vars="GCP_PROJECT_ID=$PROJECT_ID,SPRING_PROFILES_ACTIVE=pubsub" \
  --memory=512Mi \
  --port=8080 \
  --quiet

UPI_URL=$(gcloud run services describe upi-transfer-service --region=$REGION --format="value(status.url)")
echo "   ✓ upi-transfer-service: $UPI_URL"

# ── Step 5: Deploy subscriber services ──
echo ""
echo ">> [5/6] Deploying subscriber services..."

# Notification
gcloud run deploy notification-service \
  --image=gcr.io/$PROJECT_ID/notification-service \
  --region=$REGION \
  --platform=managed \
  --allow-unauthenticated \
  --set-env-vars="GCP_PROJECT_ID=$PROJECT_ID" \
  --memory=512Mi \
  --port=8081 \
  --no-cpu-throttling \
  --min-instances=1 \
  --quiet

NOTIF_URL=$(gcloud run services describe notification-service --region=$REGION --format="value(status.url)")
echo "   ✓ notification-service: $NOTIF_URL"

# Fraud Detection
gcloud run deploy fraud-detection-service \
  --image=gcr.io/$PROJECT_ID/fraud-detection-service \
  --region=$REGION \
  --platform=managed \
  --allow-unauthenticated \
  --set-env-vars="GCP_PROJECT_ID=$PROJECT_ID" \
  --memory=512Mi \
  --port=8082 \
  --no-cpu-throttling \
  --min-instances=1 \
  --quiet

FRAUD_URL=$(gcloud run services describe fraud-detection-service --region=$REGION --format="value(status.url)")
echo "   ✓ fraud-detection-service: $FRAUD_URL"

# Analytics
gcloud run deploy analytics-service \
  --image=gcr.io/$PROJECT_ID/analytics-service \
  --region=$REGION \
  --platform=managed \
  --allow-unauthenticated \
  --set-env-vars="GCP_PROJECT_ID=$PROJECT_ID" \
  --memory=512Mi \
  --port=8083 \
  --no-cpu-throttling \
  --min-instances=1 \
  --quiet

ANALYTICS_URL=$(gcloud run services describe analytics-service --region=$REGION --format="value(status.url)")
echo "   ✓ analytics-service: $ANALYTICS_URL"

# ── Step 6: Summary ──
echo ""
echo "═══════════════════════════════════════════════"
echo "  DEPLOYMENT COMPLETE!"
echo "═══════════════════════════════════════════════"
echo ""
echo "  UPI Transfer:    $UPI_URL"
echo "  Notification:    $NOTIF_URL"
echo "  Fraud Detection: $FRAUD_URL"
echo "  Analytics:       $ANALYTICS_URL"
echo ""
echo "  Pub/Sub Topic:   transaction-events"
echo "  Subscriptions:   notification-sub, fraud-detection-sub, analytics-sub"
echo ""
echo "═══════════════════════════════════════════════"
echo "  QUICK TEST:"
echo "═══════════════════════════════════════════════"
echo ""
echo "  # Check health"
echo "  curl -s $UPI_URL/actuator/health | jq"
echo ""
echo "  # Check balance"
echo "  curl -s $UPI_URL/api/balance/nag@upi | jq"
echo ""
echo "  # Make a transfer"
echo "  curl -s -X POST $UPI_URL/api/transfer \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"senderUpiId\":\"nag@upi\",\"receiverUpiId\":\"ram@upi\",\"amount\":500,\"remark\":\"cloud-run-test\"}' | jq"
echo ""
echo "  # Run full E2E test"
echo "  ./test-e2e.sh"
