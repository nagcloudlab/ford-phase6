# Lab 11: Connect Cloud Run to Cloud SQL (Real Database)

**Duration:** 45 minutes
**Objective:** Replace H2 in-memory DB with Cloud SQL (PostgreSQL) — using only environment variables and gcloud commands. Zero code changes.

> **No code changes required.** Spring Boot picks up database config from environment variables passed via Cloud Run `--set-env-vars`.

---

## Architecture

```
Cloud Run (upi-transfer-service)
    │
    └── Cloud SQL Proxy (auto) ──→ Cloud SQL (PostgreSQL)
                                       └── upidb database
                                            ├── accounts table
                                            └── transactions table
```

---

## Why This Works Without Code Changes

Spring Boot resolves config in this order:
1. Environment variables (highest priority)
2. `application.yml` (lowest priority)

Our `application.yml` has H2 config. We'll **override** it with Cloud SQL config via `--set-env-vars` at deploy time.

| application.yml (current) | Env var override (Cloud SQL) |
|---------------------------|------------------------------|
| `spring.datasource.url=jdbc:h2:mem:upidb` | `SPRING_DATASOURCE_URL=jdbc:postgresql:///<dbname>?...` |
| `spring.datasource.driver-class-name=org.h2.Driver` | `SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver` |

---

## Prerequisites

- Lab 09 completed (upi-transfer-service running on Cloud Run)

```bash
export PROJECT_ID=$(gcloud config get-value project)
export REGION=asia-south1
export SERVICE_NAME=upi-transfer-service
export INSTANCE_NAME=upi-db
export DB_NAME=upidb
export DB_USER=upi_admin
export DB_PASS=UpiSecure2026!
```

---

## Part 1: Enable Cloud SQL API

```bash
gcloud services enable sqladmin.googleapis.com --project=$PROJECT_ID
```

---

## Part 2: Create Cloud SQL Instance

```bash
gcloud sql instances create $INSTANCE_NAME \
  --database-version=POSTGRES_15 \
  --tier=db-f1-micro \
  --region=$REGION \
  --root-password=$DB_PASS \
  --storage-size=10GB \
  --storage-type=SSD \
  --availability-type=zonal \
  --project=$PROJECT_ID
```

> Takes ~5–8 minutes. This creates a managed PostgreSQL 15 instance.

### Flags explained

| Flag | Purpose |
|------|---------|
| `--tier=db-f1-micro` | Smallest (cheapest) instance — good for labs |
| `--region=asia-south1` | Same region as Cloud Run (low latency) |
| `--availability-type=zonal` | Single zone (no HA — saves cost for labs) |

### Verify

```bash
gcloud sql instances describe $INSTANCE_NAME \
  --project=$PROJECT_ID \
  --format="table(name, databaseVersion, settings.tier, state, region)"
```

---

## Part 3: Create Database and User

### Create the database

```bash
gcloud sql databases create $DB_NAME \
  --instance=$INSTANCE_NAME \
  --project=$PROJECT_ID
```

### Create an application user

```bash
gcloud sql users create $DB_USER \
  --instance=$INSTANCE_NAME \
  --password=$DB_PASS \
  --project=$PROJECT_ID
```

### Verify

```bash
echo "--- Databases ---"
gcloud sql databases list --instance=$INSTANCE_NAME \
  --project=$PROJECT_ID --format="table(NAME)"

echo "--- Users ---"
gcloud sql users list --instance=$INSTANCE_NAME \
  --project=$PROJECT_ID --format="table(NAME, HOST)"
```

---

## Part 4: Get the Connection Name

Cloud Run connects to Cloud SQL via a built-in proxy using the **connection name**.

```bash
export CONNECTION_NAME=$(gcloud sql instances describe $INSTANCE_NAME \
  --project=$PROJECT_ID \
  --format='value(connectionName)')
echo "Connection name: $CONNECTION_NAME"
```

Output format: `project-id:region:instance-name`

---

## Part 5: Add PostgreSQL Driver to the Build

We need the PostgreSQL JDBC driver + Cloud SQL socket factory in the classpath. Spring Boot 3.x fat JARs require dependencies at **build time** (post-build injection doesn't work due to classpath indexing).

We'll create a `Dockerfile.cloudsql` that adds the dependencies during the Maven build — the original `pom.xml` in your repo stays untouched.

```bash
mkdir -p ~/cloud-sql-setup && cd ~/cloud-sql-setup
```

```bash
cat > Dockerfile.cloudsql << 'DOCKERFILE'
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY upi-transfer-service/ .

# Add PostgreSQL driver + Cloud SQL socket factory at build time
# (original pom.xml in the repo is NOT modified — only inside Docker build layer)
RUN sed -i '/<\/dependencies>/i \
        <dependency>\
            <groupId>org.postgresql<\/groupId>\
            <artifactId>postgresql<\/artifactId>\
            <scope>runtime<\/scope>\
        <\/dependency>\
        <dependency>\
            <groupId>com.google.cloud.sql<\/groupId>\
            <artifactId>postgres-socket-factory<\/artifactId>\
            <version>1.22.0<\/version>\
        <\/dependency>' pom.xml

RUN chmod +x mvnw && ./mvnw package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
DOCKERFILE
```

> **Why `sed` on pom.xml in Docker?** Spring Boot 3.x uses a classpath index (`BOOT-INF/classpath.idx`) — you can't inject JARs after build. The `sed` adds dependencies during the Maven build inside Docker. Your source `pom.xml` is never modified.

### Build the Cloud SQL–ready image

```bash
cd ~ && docker build -f cloud-sql-setup/Dockerfile.cloudsql \
  -t ${SERVICE_NAME}:cloudsql .

docker tag ${SERVICE_NAME}:cloudsql \
  ${REGION}-docker.pkg.dev/${PROJECT_ID}/upi-repo/${SERVICE_NAME}:cloudsql

docker push ${REGION}-docker.pkg.dev/${PROJECT_ID}/upi-repo/${SERVICE_NAME}:cloudsql
```

---

## Part 6: Deploy with Cloud SQL Connection

```bash
gcloud run deploy $SERVICE_NAME \
  --image ${REGION}-docker.pkg.dev/${PROJECT_ID}/upi-repo/${SERVICE_NAME}:cloudsql \
  --region $REGION \
  --allow-unauthenticated \
  --add-cloudsql-instances $CONNECTION_NAME \
  --set-env-vars "\
SPRING_DATASOURCE_URL=jdbc:postgresql:///$DB_NAME?cloudSqlInstance=$CONNECTION_NAME&socketFactory=com.google.cloud.sql.postgres.SocketFactory,\
SPRING_DATASOURCE_USERNAME=$DB_USER,\
SPRING_DATASOURCE_PASSWORD=$DB_PASS,\
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver,\
SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.PostgreSQLDialect,\
SPRING_JPA_HIBERNATE_DDL_AUTO=update,\
SPRING_SQL_INIT_MODE=always,\
SPRING_JPA_DEFER_DATASOURCE_INITIALIZATION=true"
```

### Key flags explained

| Flag | Purpose |
|------|---------|
| `--add-cloudsql-instances` | Enables Cloud SQL Auth Proxy sidecar |
| `SPRING_DATASOURCE_URL` | JDBC URL using Cloud SQL socket factory |
| `SPRING_JPA_HIBERNATE_DDL_AUTO=update` | Creates tables if they don't exist (doesn't drop) |
| `SPRING_SQL_INIT_MODE=always` | Runs `data.sql` to seed accounts |

---

## Part 7: Test with Cloud SQL Backend

```bash
export SERVICE_URL=$(gcloud run services describe $SERVICE_NAME \
  --region $REGION --format='value(status.url)')

echo "--- Health ---"
curl -s $SERVICE_URL/actuator/health | python3 -m json.tool

echo "--- Balance ---"
curl -s $SERVICE_URL/api/balance/nag@upi | python3 -m json.tool

echo "--- Transfer ---"
curl -s -X POST $SERVICE_URL/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "priya@upi",
    "amount": 300,
    "remark": "Cloud SQL powered!"
  }' | python3 -m json.tool

echo "--- Transactions ---"
curl -s $SERVICE_URL/api/transactions/nag@upi | python3 -m json.tool
```

---

## Part 8: Verify Data Persists Across Restarts

With H2, data was lost on every restart. With Cloud SQL, it persists.

### Make a transfer

```bash
curl -s -X POST $SERVICE_URL/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "ram@upi",
    "receiverUpiId": "nag@upi",
    "amount": 500,
    "remark": "persistence test"
  }' | python3 -m json.tool
```

### Force a new revision (triggers restart)

```bash
gcloud run services update $SERVICE_NAME \
  --region $REGION \
  --update-env-vars "RESTART_MARKER=$(date +%s)"
```

### Check — data should still be there!

```bash
curl -s $SERVICE_URL/api/transactions/ram@upi | python3 -m json.tool
```

> With H2 this would return empty. With Cloud SQL, your transactions survive restarts.

---

## Part 9: Connect Directly to Cloud SQL (for debugging)

### Via Cloud SQL Auth Proxy locally

```bash
# Install Cloud SQL Auth Proxy (if not installed)
gcloud components install cloud-sql-proxy 2>/dev/null

# Start proxy in background
cloud-sql-proxy $CONNECTION_NAME --port=5432 &
PROXY_PID=$!

# Connect with psql
psql "host=127.0.0.1 port=5432 dbname=$DB_NAME user=$DB_USER password=$DB_PASS"
```

### Run some queries

```sql
-- List all accounts
SELECT * FROM accounts;

-- List all transactions
SELECT * FROM transactions ORDER BY timestamp DESC;

-- Check balances after transfers
SELECT upi_id, holder_name, balance FROM accounts ORDER BY balance DESC;

-- Exit
\q
```

### Stop proxy

```bash
kill $PROXY_PID
```

### Or use gcloud connect (simpler)

```bash
gcloud sql connect $INSTANCE_NAME \
  --user=$DB_USER \
  --database=$DB_NAME \
  --project=$PROJECT_ID
```

---

## Part 10: View Cloud SQL Metrics

```bash
# Instance details
gcloud sql instances describe $INSTANCE_NAME \
  --project=$PROJECT_ID \
  --format="yaml(settings.tier, settings.dataDiskSizeGb, state, ipAddresses)"
```

> Go to **Cloud Console > SQL > upi-db > Metrics** to see:
> - CPU utilization
> - Memory usage
> - Active connections
> - Read/Write operations
> - Storage usage

---

## Part 11: H2 vs Cloud SQL — The Side-by-Side

| Aspect | H2 (In-Memory) | Cloud SQL (PostgreSQL) |
|--------|----------------|----------------------|
| **Data persistence** | Lost on every restart | Persists forever |
| **Max connections** | Single JVM | Hundreds of concurrent |
| **Backups** | None | Automatic daily |
| **Replication** | Impossible | One-click HA |
| **SQL dialect** | H2 (limited) | Full PostgreSQL |
| **Cost** | Free | ~$7/month (db-f1-micro) |
| **Use case** | Dev/testing | Production |

---

## Part 12: Cleanup

```bash
# Redeploy with H2 (revert to original image)
gcloud run deploy $SERVICE_NAME \
  --image ${REGION}-docker.pkg.dev/${PROJECT_ID}/upi-repo/${SERVICE_NAME}:v1 \
  --region $REGION \
  --clear-env-vars \
  --remove-cloudsql-instances $CONNECTION_NAME

# Delete Cloud SQL instance (THIS DELETES ALL DATA)
gcloud sql instances delete $INSTANCE_NAME \
  --project=$PROJECT_ID --quiet

# Remove local files
rm -rf ~/cloud-sql-setup

echo "Cloud SQL resources cleaned up!"
```

---

## Checkpoint

- [ ] Created Cloud SQL PostgreSQL instance
- [ ] Created database and user
- [ ] Deployed to Cloud Run with Cloud SQL env vars (no code changes!)
- [ ] Tested all APIs against real PostgreSQL
- [ ] Verified data persists across restarts
- [ ] Connected directly to Cloud SQL for debugging
- [ ] Compared H2 vs Cloud SQL
- [ ] Cleaned up all resources
