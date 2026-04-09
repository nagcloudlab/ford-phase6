#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Cleanup: Delete all Cloud Run services and Pub/Sub resources
# Usage: ./cleanup-cloudrun.sh
# ═══════════════════════════════════════════════════════════════

set -e

PROJECT_ID=$(gcloud config get-value project 2>/dev/null)
REGION=us-central1

echo "═══════════════════════════════════════════════"
echo "  Cleaning up UPI Platform (Cloud Run)"
echo "  Project: $PROJECT_ID"
echo "═══════════════════════════════════════════════"
echo ""

# Delete Cloud Run services
echo ">> Deleting Cloud Run services..."
for svc in upi-transfer-service notification-service fraud-detection-service analytics-service; do
  gcloud run services delete $svc --region=$REGION --quiet 2>/dev/null && echo "   Deleted: $svc" || echo "   Not found: $svc"
done

# Delete Pub/Sub subscriptions
echo ""
echo ">> Deleting Pub/Sub subscriptions..."
for sub in notification-sub fraud-detection-sub analytics-sub dlq-monitor-sub; do
  gcloud pubsub subscriptions delete $sub --quiet 2>/dev/null && echo "   Deleted: $sub" || echo "   Not found: $sub"
done

# Delete Pub/Sub topics
echo ""
echo ">> Deleting Pub/Sub topics..."
for topic in transaction-events transaction-events-dlq; do
  gcloud pubsub topics delete $topic --quiet 2>/dev/null && echo "   Deleted: $topic" || echo "   Not found: $topic"
done

# Delete container images
echo ""
echo ">> Deleting container images..."
for svc in upi-transfer-service notification-service fraud-detection-service analytics-service; do
  gcloud container images delete gcr.io/$PROJECT_ID/$svc --force-delete-tags --quiet 2>/dev/null && echo "   Deleted image: $svc" || echo "   Not found: $svc"
done

echo ""
echo "═══════════════════════════════════════════════"
echo "  Cleanup complete!"
echo "═══════════════════════════════════════════════"
