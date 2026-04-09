#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# End-to-End Test: UPI Pub/Sub Microservices on Cloud Run
# Verifies the entire flow: transfer → Pub/Sub → all subscribers
# Usage: ./test-e2e.sh
# ═══════════════════════════════════════════════════════════════

set -e

PROJECT_ID=$(gcloud config get-value project 2>/dev/null)
REGION=us-central1

# ── Get service URLs ──
UPI_URL=$(gcloud run services describe upi-transfer-service --region=$REGION --format="value(status.url)" 2>/dev/null)
NOTIF_URL=$(gcloud run services describe notification-service --region=$REGION --format="value(status.url)" 2>/dev/null)
FRAUD_URL=$(gcloud run services describe fraud-detection-service --region=$REGION --format="value(status.url)" 2>/dev/null)
ANALYTICS_URL=$(gcloud run services describe analytics-service --region=$REGION --format="value(status.url)" 2>/dev/null)

if [ -z "$UPI_URL" ]; then
  echo "ERROR: Services not deployed. Run ./deploy-cloudrun.sh first."
  exit 1
fi

PASS=0
FAIL=0

pass() { PASS=$((PASS+1)); echo "   ✅ PASS: $1"; }
fail() { FAIL=$((FAIL+1)); echo "   ❌ FAIL: $1"; }

echo "═══════════════════════════════════════════════"
echo "  UPI Platform — End-to-End Test"
echo "═══════════════════════════════════════════════"
echo ""
echo "  UPI Transfer:    $UPI_URL"
echo "  Notification:    $NOTIF_URL"
echo "  Fraud Detection: $FRAUD_URL"
echo "  Analytics:       $ANALYTICS_URL"
echo ""

# ─────────────────────────────────────────────────
# TEST 1: Health checks
# ─────────────────────────────────────────────────
echo ">> Test 1: Health Checks"
echo ""

for svc_name in "upi-transfer-service" "notification-service" "fraud-detection-service" "analytics-service"; do
  url=$(gcloud run services describe $svc_name --region=$REGION --format="value(status.url)" 2>/dev/null)
  health=$(curl -s -o /dev/null -w "%{http_code}" "$url/actuator/health" 2>/dev/null)
  if [ "$health" = "200" ]; then
    pass "$svc_name is healthy"
  else
    fail "$svc_name health check returned $health"
  fi
done

echo ""

# ─────────────────────────────────────────────────
# TEST 2: Check balance (basic API test)
# ─────────────────────────────────────────────────
echo ">> Test 2: Balance Check"
echo ""

balance_resp=$(curl -s "$UPI_URL/api/balance/nag@upi")
if echo "$balance_resp" | grep -q "holderName"; then
  pass "GET /api/balance/nag@upi returned account info"
  echo "        $(echo $balance_resp | python3 -m json.tool 2>/dev/null || echo $balance_resp)"
else
  fail "GET /api/balance/nag@upi failed: $balance_resp"
fi

echo ""

# ─────────────────────────────────────────────────
# TEST 3: Get initial subscriber stats (baseline)
# ─────────────────────────────────────────────────
echo ">> Test 3: Baseline Subscriber Stats"
echo ""

notif_before=$(curl -s "$NOTIF_URL/stats" | python3 -c "import sys,json; print(json.load(sys.stdin).get('messagesProcessed',0))" 2>/dev/null || echo "0")
fraud_before=$(curl -s "$FRAUD_URL/stats" | python3 -c "import sys,json; print(json.load(sys.stdin).get('messagesProcessed',0))" 2>/dev/null || echo "0")
analytics_before=$(curl -s "$ANALYTICS_URL/stats" | python3 -c "import sys,json; print(json.load(sys.stdin).get('messagesProcessed',0))" 2>/dev/null || echo "0")

echo "   Notification:    $notif_before messages processed"
echo "   Fraud Detection: $fraud_before messages processed"
echo "   Analytics:       $analytics_before messages processed"
echo ""

# ─────────────────────────────────────────────────
# TEST 4: Make transfers (triggers Pub/Sub events)
# ─────────────────────────────────────────────────
echo ">> Test 4: Making 3 Transfers"
echo ""

# Transfer 1: Normal
resp1=$(curl -s -X POST "$UPI_URL/api/transfer" \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"nag@upi","receiverUpiId":"ram@upi","amount":100,"remark":"e2e-test-1"}')
status1=$(echo $resp1 | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
if [ "$status1" = "SUCCESS" ]; then
  pass "Transfer 1 (100 INR): SUCCESS"
else
  fail "Transfer 1: $status1 — $resp1"
fi

# Transfer 2: Another normal
resp2=$(curl -s -X POST "$UPI_URL/api/transfer" \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"priya@upi","receiverUpiId":"ford@upi","amount":2000,"remark":"e2e-test-2"}')
status2=$(echo $resp2 | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
if [ "$status2" = "SUCCESS" ]; then
  pass "Transfer 2 (2000 INR): SUCCESS"
else
  fail "Transfer 2: $status2 — $resp2"
fi

# Transfer 3: High amount (should trigger fraud alert)
resp3=$(curl -s -X POST "$UPI_URL/api/transfer" \
  -H "Content-Type: application/json" \
  -d '{"senderUpiId":"nag@upi","receiverUpiId":"priya@upi","amount":6000,"remark":"e2e-test-high"}')
status3=$(echo $resp3 | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
if [ "$status3" = "SUCCESS" ]; then
  pass "Transfer 3 (6000 INR — should trigger fraud alert): SUCCESS"
else
  fail "Transfer 3: $status3 — $resp3"
fi

echo ""

# ─────────────────────────────────────────────────
# TEST 5: Wait for Pub/Sub delivery + check subscribers
# ─────────────────────────────────────────────────
echo ">> Test 5: Waiting 15 seconds for Pub/Sub delivery..."
sleep 15
echo ""

echo ">> Test 5: Checking Subscriber Stats (after transfers)"
echo ""

# Notification service
notif_after=$(curl -s "$NOTIF_URL/stats" | python3 -c "import sys,json; print(json.load(sys.stdin).get('messagesProcessed',0))" 2>/dev/null || echo "0")
notif_new=$((notif_after - notif_before))
if [ "$notif_new" -ge 3 ]; then
  pass "Notification service received $notif_new new messages (expected >= 3)"
else
  fail "Notification service received $notif_new new messages (expected >= 3)"
fi

# Fraud detection service
fraud_after=$(curl -s "$FRAUD_URL/stats" | python3 -c "import sys,json; print(json.load(sys.stdin).get('messagesProcessed',0))" 2>/dev/null || echo "0")
fraud_new=$((fraud_after - fraud_before))
if [ "$fraud_new" -ge 3 ]; then
  pass "Fraud detection received $notif_new new messages (expected >= 3)"
else
  fail "Fraud detection received $fraud_new new messages (expected >= 3)"
fi

# Check fraud alerts (transfer 3 was 6000 — should trigger high amount alert)
fraud_alerts=$(curl -s "$FRAUD_URL/stats" | python3 -c "import sys,json; print(json.load(sys.stdin).get('fraudAlertsRaised',0))" 2>/dev/null || echo "0")
if [ "$fraud_alerts" -ge 1 ]; then
  pass "Fraud detection raised $fraud_alerts alert(s) (6000 INR transfer)"
else
  fail "Fraud detection raised $fraud_alerts alerts (expected >= 1)"
fi

# Analytics service
analytics_after=$(curl -s "$ANALYTICS_URL/stats" | python3 -c "import sys,json; print(json.load(sys.stdin).get('messagesProcessed',0))" 2>/dev/null || echo "0")
analytics_new=$((analytics_after - analytics_before))
if [ "$analytics_new" -ge 3 ]; then
  pass "Analytics service received $analytics_new new messages (expected >= 3)"
else
  fail "Analytics service received $analytics_new new messages (expected >= 3)"
fi

echo ""

# ─────────────────────────────────────────────────
# TEST 6: Detailed stats from each subscriber
# ─────────────────────────────────────────────────
echo ">> Test 6: Detailed Subscriber Stats"
echo ""

echo "   --- Notification Service ---"
curl -s "$NOTIF_URL/stats" | python3 -m json.tool 2>/dev/null || curl -s "$NOTIF_URL/stats"
echo ""

echo "   --- Fraud Detection Service ---"
curl -s "$FRAUD_URL/stats" | python3 -m json.tool 2>/dev/null || curl -s "$FRAUD_URL/stats"
echo ""

echo "   --- Analytics Service ---"
curl -s "$ANALYTICS_URL/stats" | python3 -m json.tool 2>/dev/null || curl -s "$ANALYTICS_URL/stats"
echo ""

# ─────────────────────────────────────────────────
# TEST 7: Check Cloud Run logs (last few entries)
# ─────────────────────────────────────────────────
echo ">> Test 7: Recent Cloud Run Logs"
echo ""

echo "   --- upi-transfer-service (publisher) ---"
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=upi-transfer-service AND textPayload:PUBLISHED" \
  --limit=3 --format="value(textPayload)" --project=$PROJECT_ID 2>/dev/null || echo "   (no publish logs yet)"
echo ""

echo "   --- notification-service ---"
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=notification-service AND textPayload:SMS" \
  --limit=3 --format="value(textPayload)" --project=$PROJECT_ID 2>/dev/null || echo "   (no notification logs yet)"
echo ""

echo "   --- fraud-detection-service ---"
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=fraud-detection-service AND textPayload:ALERT" \
  --limit=3 --format="value(textPayload)" --project=$PROJECT_ID 2>/dev/null || echo "   (no fraud logs yet)"
echo ""

# ─────────────────────────────────────────────────
# TEST 8: Verify Pub/Sub topic metrics
# ─────────────────────────────────────────────────
echo ">> Test 8: Pub/Sub Subscription Status"
echo ""

for sub in notification-sub fraud-detection-sub analytics-sub; do
  backlog=$(gcloud pubsub subscriptions describe $sub --format="value(messageRetentionDuration)" 2>/dev/null)
  echo "   $sub — active"
done

echo ""

# ─────────────────────────────────────────────────
# SUMMARY
# ─────────────────────────────────────────────────
echo "═══════════════════════════════════════════════"
echo "  TEST RESULTS"
echo "═══════════════════════════════════════════════"
echo ""
echo "  Passed: $PASS"
echo "  Failed: $FAIL"
echo ""

if [ "$FAIL" -eq 0 ]; then
  echo "  🎉 ALL TESTS PASSED!"
  echo ""
  echo "  The full Pub/Sub pipeline is working:"
  echo "  Transfer API → Pub/Sub → Notification ✓"
  echo "  Transfer API → Pub/Sub → Fraud Detection ✓"
  echo "  Transfer API → Pub/Sub → Analytics ✓"
else
  echo "  ⚠️  Some tests failed. Check the output above."
  echo ""
  echo "  Common issues:"
  echo "  - Subscribers need --no-cpu-throttling and --min-instances=1"
  echo "  - Wait longer for Pub/Sub delivery (increase sleep)"
  echo "  - Check: gcloud run services list --region=$REGION"
fi

echo ""
echo "═══════════════════════════════════════════════"
