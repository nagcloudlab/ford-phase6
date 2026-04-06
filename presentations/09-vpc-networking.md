# VPC & Networking
## The Security Backbone of Cloud Architecture

---

## Why Networking is Where Architects Stand Out
Most beginners skip networking. Then they wonder why:
- Their database got hacked
- Their internal services are exposed to the internet
- Their architecture has no security layers

**Networking is the difference between a demo and a production system.**

---

## What is a VPC?
**VPC = Virtual Private Cloud — your own private network inside Google Cloud**

Think of it as your company's office network, but in the cloud:
- Your devices (VMs, databases) are connected
- You control who can enter
- Outsiders can't see what's inside

### Structure
```
VPC (your private network)
 ├── Subnet-1 (Mumbai — 10.0.1.0/24)
 ├── Subnet-2 (US — 10.0.2.0/24)
 └── Resources (VMs, DBs, Cloud Run services)
```

---

## Why VPC Exists
### Without VPC
- Everything is public on the internet
- No isolation between services
- Anyone can reach your database
- **Security nightmare**

### With VPC
- Private communication between your services
- Controlled access points
- Database hidden from internet
- **Secure by design**

---

## Subnets: Carving Up Your Network
**Subnet = A range of IP addresses inside your VPC, tied to a region**

### Example
| Subnet | IP Range | Region | Contains |
|---|---|---|---|
| app-subnet | 10.0.1.0/24 | asia-south1 | App servers |
| db-subnet | 10.0.2.0/24 | asia-south1 | Databases |

### Key Facts
- Subnets are **regional** (span all zones in a region)
- Resources inside a subnet get an **internal IP**
- Different subnets can talk to each other within the same VPC

---

## The Architecture That Gets Hacked
### Bad Design: Everything Public
```
User → VM (Public IP) → Database (Public IP)
```
- VM directly exposed to internet
- Database accessible by anyone
- One vulnerability = total breach

**This is how data leaks happen.**

---

## The Architecture That's Secure
### Good Design: Public Entry, Private Backend
```
User
  ↓
Load Balancer (Public — the ONLY public entry point)
  ↓
App Server (Private IP — inside VPC)
  ↓
Database (Private IP — inside VPC)
```

### Golden Rule
**Only expose the Load Balancer. Everything else stays private.**

---

## Firewall Rules: Your Security Gates
**Firewalls control what traffic can enter or leave your VPC**

### Default Behavior
- **All incoming traffic is DENIED** by default
- You must explicitly allow what you need

### Common Rules
| Rule | Protocol | Port | Purpose |
|---|---|---|---|
| allow-ssh | TCP | 22 | SSH access to VMs |
| allow-http | TCP | 80 | Web traffic |
| allow-app | TCP | 8080 | Application port |
| allow-health | TCP | varies | Load balancer health checks |

### Example Command
```bash
gcloud compute firewall-rules create allow-http \
  --network=my-vpc \
  --allow tcp:80 \
  --source-ranges=0.0.0.0/0
```

---

## Internal vs External IP
| Type | Visible To | Use For |
|---|---|---|
| **Internal IP** | Only resources inside VPC | Service-to-service communication |
| **External IP** | The entire internet | Public-facing endpoints |

### Best Practice
| Resource | IP Type |
|---|---|
| Load Balancer | External (public entry) |
| App servers | Internal only |
| Database | Internal only |
| Cache/queues | Internal only |

**Minimize external IPs. Every public IP is an attack surface.**

---

## Service-to-Service Communication
### How do microservices talk inside your VPC?

**Option 1: Internal HTTP**
```
Order Service → (internal IP) → Payment Service
```
- Direct, fast, synchronous

**Option 2: Pub/Sub (Async)**
```
Order Service → Pub/Sub → Payment Service
```
- Decoupled, resilient, handles spikes

**Prefer private communication + event-driven patterns.**

---

## Hands-On: Build a VPC

### Create VPC
```bash
gcloud compute networks create my-vpc \
  --subnet-mode=custom
```

### Create Subnet
```bash
gcloud compute networks subnets create app-subnet \
  --network=my-vpc \
  --range=10.0.1.0/24 \
  --region=asia-south1
```

### Add Firewall Rule
```bash
gcloud compute firewall-rules create allow-ssh \
  --network=my-vpc \
  --allow tcp:22
```

### Launch VM in VPC
```bash
gcloud compute instances create app-vm \
  --network=my-vpc \
  --subnet=app-subnet \
  --zone=asia-south1-a
```

---

## Real Architecture: UPI-like System
```
User
  ↓
Global Load Balancer (only public endpoint)
  ↓
Cloud Run (Private — inside VPC connector)
  ↓
Cloud SQL (Private IP — no internet access)
  ↓
Pub/Sub (internal — event processing)
```

**Key Principles:**
- No direct database exposure
- All services communicate internally
- Single controlled entry point
- Secure by design, not by accident

---

## Real-World Mistakes
| Mistake | Consequence | Prevention |
|---|---|---|
| Making everything public | Security breach | Private by default, public by exception |
| No firewall rules | Open system, anyone can connect | Explicit allow rules only |
| Wrong subnet planning | IP conflicts, routing issues | Plan IP ranges upfront |
| Direct DB exposure | Data theft, ransomware | Always use private IP for databases |
| Flat network (no segmentation) | Lateral movement in breaches | Separate subnets by function |

---

## Expert Takeaways
- **VPC = the foundation** of all cloud security
- **Subnets** = IP segmentation by region and function
- **Firewalls** = your control layer — deny by default, allow explicitly
- **Design pattern:** Public entry → Private backend (always)
- **Networking = architecture backbone** — get this right and everything else is easier

---

## What's Next?
**Next:** Load Balancing & Traffic Management — the gateway to high availability and global performance.
