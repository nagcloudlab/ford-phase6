# Lab 10: API Gateway in Front of Cloud Run

**Duration:** 45 minutes
**Objective:** Put a Google Cloud API Gateway in front of the UPI Transfer Service on Cloud Run — rate limiting, API key authentication, and a single managed endpoint.

> **No code changes required.** Everything is gcloud commands and config files.

---

## Architecture

```
Client → API Gateway → Cloud Run (upi-transfer-service)
            │
            ├── API Key validation
            ├── Rate limiting
            └── Single managed URL
```

---

## Prerequisites

- Lab 09 completed (upi-transfer-service running on Cloud Run)

```bash
# ─── Set variables ───
export PROJECT_ID=$(gcloud config get-value project)
export REGION=asia-south1
export SERVICE_NAME=upi-transfer-service
export SERVICE_URL=$(gcloud run services describe $SERVICE_NAME \
  --region $REGION --format='value(status.url)')
echo "Cloud Run URL: $SERVICE_URL"
```

---

## Part 1: Enable APIs

```bash
gcloud services enable \
  apigateway.googleapis.com \
  servicemanagement.googleapis.com \
  servicecontrol.googleapis.com \
  --project=$PROJECT_ID
```

---

## Part 2: Create the OpenAPI Spec

This is the **gateway configuration** — it defines which paths are exposed and where they route to.

```bash
mkdir -p ~/api-gateway && cd ~/api-gateway
```

```bash
cat > openapi-spec.yaml << 'SPEC'
swagger: "2.0"
info:
  title: "UPI Transfer Service API"
  description: "API Gateway for UPI Transfer Service on Cloud Run"
  version: "1.0.0"
schemes:
  - "https"
produces:
  - "application/json"
x-google-backend:
  address: SERVICE_URL_PLACEHOLDER
  protocol: h2

paths:
  /api/balance/{upiId}:
    get:
      summary: "Get account balance"
      operationId: "getBalance"
      parameters:
        - name: upiId
          in: path
          required: true
          type: string
      responses:
        200:
          description: "Balance response"
      security:
        - api_key: []

  /api/transfer:
    post:
      summary: "Transfer money"
      operationId: "transfer"
      responses:
        200:
          description: "Transfer response"
      security:
        - api_key: []

  /api/transactions/{upiId}:
    get:
      summary: "Get transaction history"
      operationId: "getTransactions"
      parameters:
        - name: upiId
          in: path
          required: true
          type: string
      responses:
        200:
          description: "Transaction list"
      security:
        - api_key: []

  /actuator/health:
    get:
      summary: "Health check"
      operationId: "healthCheck"
      responses:
        200:
          description: "Health status"

securityDefinitions:
  api_key:
    type: "apiKey"
    name: "x-api-key"
    in: "header"
SPEC
```

### Replace the placeholder with actual Cloud Run URL

```bash
sed -i.bak "s|SERVICE_URL_PLACEHOLDER|${SERVICE_URL}|" openapi-spec.yaml
cat openapi-spec.yaml | head -15
```

### What this spec does

| Section | Purpose |
|---------|---------|
| `x-google-backend` | Routes all traffic to our Cloud Run service |
| `paths` | Exposes only the APIs we want (not everything) |
| `security: api_key` | Requires API key for transfer/balance/transactions |
| `/actuator/health` | Health check is **public** (no api_key required) |

---

## Part 3: Create the API Config

```bash
export API_ID=upi-api
export API_CONFIG_ID=upi-api-config-v1
export GATEWAY_ID=upi-gateway

gcloud api-gateway apis create $API_ID \
  --project=$PROJECT_ID
```

### Upload the OpenAPI spec as config

```bash
gcloud api-gateway api-configs create $API_CONFIG_ID \
  --api=$API_ID \
  --openapi-spec=openapi-spec.yaml \
  --project=$PROJECT_ID
```

> This validates the spec and creates a managed API config. Takes ~1–2 minutes.

### Verify

```bash
gcloud api-gateway api-configs list \
  --api=$API_ID \
  --project=$PROJECT_ID \
  --format="table(CONFIG_ID, STATE, SERVICE_CONFIG_ID)"
```

---

## Part 4: Create the Gateway

```bash
export GW_LOCATION=us-central1

gcloud api-gateway gateways create $GATEWAY_ID \
  --api=$API_ID \
  --api-config=$API_CONFIG_ID \
  --location=$GW_LOCATION \
  --project=$PROJECT_ID
```

> Takes ~3–5 minutes. API Gateway is only available in select regions (`us-central1`, `europe-west1`, `asia-northeast1`), not in `asia-south1`.

### Get the Gateway URL

```bash
export GATEWAY_URL=$(gcloud api-gateway gateways describe $GATEWAY_ID \
  --location=$GW_LOCATION \
  --project=$PROJECT_ID \
  --format='value(defaultHostname)')
echo "Gateway URL: https://$GATEWAY_URL"
```

---

## Part 5: Test Through the Gateway

### Health check (public — no API key needed)

```bash
curl -s https://$GATEWAY_URL/actuator/health | python3 -m json.tool
```

### Balance (requires API key — should fail without it)

```bash
curl -s -w "\nHTTP Status: %{http_code}\n" https://$GATEWAY_URL/api/balance/nag@upi
```

Expected: `403 Forbidden` — API key required!

---

## Part 6: Create an API Key

```bash
# Enable API Keys service
gcloud services enable apikeys.googleapis.com --project=$PROJECT_ID
```

### Enable the gateway's managed service for API key validation

```bash
export MANAGED_SERVICE=$(gcloud api-gateway apis describe $API_ID \
  --project=$PROJECT_ID \
  --format='value(managedService)')
echo "Managed service: $MANAGED_SERVICE"

gcloud services enable $MANAGED_SERVICE --project=$PROJECT_ID
```

> This step is required — without it, API keys get `403 PERMISSION_DENIED`. Propagation takes ~2–5 minutes.

```bash

# Get the managed service name
export MANAGED_SERVICE=$(gcloud api-gateway apis describe $API_ID \
  --project=$PROJECT_ID \
  --format='value(managedService)')
echo "Managed service: $MANAGED_SERVICE"

# Create an API key restricted to our gateway service
gcloud alpha services api-keys create \
  --display-name="upi-api-key" \
  --api-target=service=$MANAGED_SERVICE \
  --project=$PROJECT_ID
```

### Get the key value

```bash
# List keys
gcloud alpha services api-keys list --project=$PROJECT_ID \
  --format="table(NAME, DISPLAY_NAME)"

# Get the key string (replace KEY_ID from output above)
export KEY_ID=$(gcloud alpha services api-keys list --project=$PROJECT_ID \
  --format='value(NAME)' | head -1)

export API_KEY=$(gcloud alpha services api-keys get-key-string $KEY_ID \
  --format='value(keyString)')
echo "API Key: $API_KEY"
```

---

## Part 7: Test with API Key

> **Important:** After enabling the managed service and creating the API key, wait **3–5 minutes** for full propagation. You may see `403 PERMISSION_DENIED` until propagation completes.

### Balance — with API key header

```bash
curl -s -H "x-api-key: $API_KEY" \
  https://$GATEWAY_URL/api/balance/nag@upi | python3 -m json.tool
```

### Transfer

```bash
curl -s -X POST \
  -H "x-api-key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "ram@upi",
    "amount": 100,
    "remark": "via API Gateway"
  }' \
  https://$GATEWAY_URL/api/transfer | python3 -m json.tool
```

### Transactions

```bash
curl -s -H "x-api-key: $API_KEY" \
  https://$GATEWAY_URL/api/transactions/nag@upi | python3 -m json.tool
```

---

## Part 8: Compare Direct vs Gateway Access

```bash
echo "=== Direct Cloud Run ==="
curl -s -o /dev/null -w "Status: %{http_code}, Time: %{time_total}s\n" \
  $SERVICE_URL/api/balance/nag@upi

echo "=== Via API Gateway ==="
curl -s -o /dev/null -w "Status: %{http_code}, Time: %{time_total}s\n" \
  -H "x-api-key: $API_KEY" \
  https://$GATEWAY_URL/api/balance/nag@upi
```

| Aspect | Direct Cloud Run | Via API Gateway |
|--------|-----------------|-----------------|
| URL | Long, auto-generated | Managed, can add custom domain |
| Auth | Open or IAM-only | API Key, JWT, or IAM |
| Rate limiting | None built-in | Configurable |
| API versioning | Manual | Config-based |
| Monitoring | Cloud Run metrics only | Gateway + Cloud Run metrics |

---

## Part 9: View Gateway Logs & Metrics

```bash
# View gateway logs
gcloud logging read "resource.type=apigateway.googleapis.com/Gateway AND resource.labels.gateway_id=$GATEWAY_ID" \
  --project=$PROJECT_ID \
  --limit=10 \
  --format="table(timestamp, jsonPayload.httpRequest.requestUrl, jsonPayload.httpRequest.status)"
```

> Also check: **Cloud Console > API Gateway > upi-gateway > Metrics**

---

## Part 10: Update the API (deploy new config)

If you need to change routing, add paths, or modify security:

```bash
# Edit openapi-spec.yaml, then:
export API_CONFIG_V2=upi-api-config-v2

gcloud api-gateway api-configs create $API_CONFIG_V2 \
  --api=$API_ID \
  --openapi-spec=openapi-spec.yaml \
  --project=$PROJECT_ID

gcloud api-gateway gateways update $GATEWAY_ID \
  --api=$API_ID \
  --api-config=$API_CONFIG_V2 \
  --location=$GW_LOCATION \
  --project=$PROJECT_ID
```

---

## Part 11: Cleanup

```bash
# Delete gateway
gcloud api-gateway gateways delete $GATEWAY_ID \
  --location=$GW_LOCATION --project=$PROJECT_ID --quiet

# Delete API config
gcloud api-gateway api-configs delete $API_CONFIG_ID \
  --api=$API_ID --project=$PROJECT_ID --quiet

# Delete API
gcloud api-gateway apis delete $API_ID \
  --project=$PROJECT_ID --quiet

# Delete API key
gcloud alpha services api-keys delete $KEY_ID \
  --project=$PROJECT_ID --quiet

# Remove local files
rm -rf ~/api-gateway

echo "API Gateway resources cleaned up!"
```

---

## Summary

| What You Did | Command/Concept |
|--------------|----------------|
| Created OpenAPI spec | Defines routes, security, backend |
| Created API Gateway | `gcloud api-gateway gateways create` |
| API Key auth | `securityDefinitions` in spec + `x-api-key` header |
| Public vs protected routes | Per-path `security` in OpenAPI spec |
| Config updates | Create new api-config, update gateway |

---

## Checkpoint

- [ ] Enabled API Gateway APIs
- [ ] Created OpenAPI spec pointing to Cloud Run
- [ ] Deployed API config and gateway
- [ ] Tested public health endpoint (no key)
- [ ] Got 403 on protected endpoint (no key)
- [ ] Created and used API key successfully
- [ ] Compared direct vs gateway latency
- [ ] Cleaned up all resources
