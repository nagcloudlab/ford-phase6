# Cloud SQL: Managed Databases
## Transactions, Reliability & Zero DBA Headaches

---

## The Database Dilemma
Your app needs a relational database. Two options:

### Option A: Install on VM (The Hard Way)
- Install MySQL/PostgreSQL manually
- Configure replication yourself
- Manage backups, patching, security
- Handle crash recovery at 3 AM

### Option B: Cloud SQL (The Smart Way)
- Managed by Google
- Automated backups, failover, patching
- You focus on schema, queries & business logic

**Production rule: Always use managed databases.**

---

## What is Cloud SQL?
**Fully managed relational database service on GCP**

### You Focus On:
- Database schema design
- Writing queries
- Transaction logic

### Google Handles:
- Infrastructure & hardware
- Automated daily backups
- OS & DB engine patching
- High availability failover
- Monitoring & alerts

**Cloud SQL = Your database, without the DBA headache.**

---

## Supported Databases
| Engine | Best For | Recommendation |
|---|---|---|
| **PostgreSQL** | Modern apps, advanced features | Recommended |
| **MySQL** | Legacy apps, wide compatibility | Good choice |
| **SQL Server** | Microsoft ecosystem | Specific needs |

**Default choice: PostgreSQL** — better features, modern tooling, strong community.

---

## Cloud SQL Architecture
```
Your App (Cloud Run / VM)
        ↓
  Cloud SQL Instance
        ↓
   Primary Database
        ↓
  Read Replica (for scaling reads)
        ↓
  Standby (for automatic failover)
```

### Key Components
- **Primary Instance** — your main database, handles all writes
- **Read Replica** — copy for read-heavy queries (analytics, reports)
- **HA Standby** — automatic failover if primary goes down

---

## Connectivity: How Your App Talks to the DB

| Method | Security | Best For |
|---|---|---|
| Public IP | Low — exposed to internet | Quick testing only |
| Private IP | High — inside VPC only | Production recommended |
| Cloud SQL Proxy | Highest — encrypted tunnel | Best practice |

### Recommended Production Setup
```
Cloud Run → Cloud SQL Proxy → Cloud SQL (Private IP)
```
- No IP exposure
- Encrypted connection
- IAM-authenticated

---

## Hands-On: Set Up Cloud SQL

### Create Instance
1. Go to **SQL → Create Instance**
2. Choose **PostgreSQL**
3. Configure: Name `my-postgres`, Region `asia-south1`, Set password
4. Click **Create** (takes ~5 minutes)

### Create Database & Table
```sql
CREATE DATABASE myapp;

CREATE TABLE users (
  id SERIAL PRIMARY KEY,
  name VARCHAR(100),
  email VARCHAR(100)
);

INSERT INTO users (name, email)
VALUES ('Nag', 'nag@test.com');
```

---

## Spring Boot Integration

### application.properties
```properties
spring.datasource.url=jdbc:postgresql://HOST:5432/myapp
spring.datasource.username=postgres
spring.datasource.password=yourpassword
spring.jpa.hibernate.ddl-auto=update
```

### Entity
```java
@Entity
public class User {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private String email;
}
```

### Production Tips
- Use **HikariCP** connection pooling (Spring Boot default)
- Use **Cloud SQL Java Connector** for secure auth
- Never hardcode passwords — use **Secret Manager**

---

## Transactions: The Heart of Financial Systems
### Example: UPI Money Transfer
```
Step 1: Debit sender's account   (-₹500)
Step 2: Credit receiver's account (+₹500)
Step 3: Commit both or rollback both
```

### What If Step 2 Fails?
- **Without transactions:** Sender lost ₹500, receiver got nothing. Money vanished.
- **With ACID transactions:** Both steps rollback. Nobody loses money.

### ACID Guarantees
| Property | Meaning |
|---|---|
| **Atomicity** | All or nothing — no partial updates |
| **Consistency** | DB always in valid state |
| **Isolation** | Concurrent transactions don't interfere |
| **Durability** | Committed data survives crashes |

**Cloud SQL guarantees ACID. This is why you use relational DBs for money.**

---

## Scaling Strategies
| Strategy | What | When |
|---|---|---|
| **Vertical scaling** | Increase CPU/RAM of instance | Quick fix for growing load |
| **Read replicas** | Separate instance for read queries | Read-heavy workloads |
| **Connection pooling** | Reuse DB connections | Many app instances |

### Backups & Recovery
- **Automated backups** — daily, retained for 7 days
- **Point-in-time recovery** — restore to any second in the last 7 days
- **On-demand backups** — before risky changes

**Example:** Accidentally deleted a table? Restore to 5 minutes before the mistake.

---

## Real-World Mistakes
| Mistake | Impact | Fix |
|---|---|---|
| Using public IP in production | Database exposed to internet | Use private IP + SQL Proxy |
| No automated backups | Data loss on failure | Enable automated backups |
| Too many DB connections | Database crashes | Use connection pooling |
| No read replicas for analytics | Prod DB slowed by reports | Add read replica for queries |
| Hardcoding credentials | Security vulnerability | Use Secret Manager |

---

## Expert Takeaways
- **Cloud SQL = default relational DB** for GCP applications
- **PostgreSQL** is the recommended engine
- **Always use:** Private IP + Cloud SQL Proxy in production
- **Connection pooling** is mandatory, not optional
- **Transactions = the core** of financial & business-critical systems
- **Database design impacts everything** — get it right early

---

## What's Next?
**Next:** VPC & Networking — the security backbone of your cloud architecture. Where everything connects.
