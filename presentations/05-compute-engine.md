# Compute Engine: VMs Deep Dive
## Understanding Cloud from the Ground Up

---

## Why Start with VMs?
Even if you'll use Cloud Run in production, understanding VMs teaches you:
- How cloud infrastructure actually works
- What's happening "under the hood"
- When you truly need full control

**VMs are the foundation. Everything else is abstraction built on top.**

---

## What is a VM, Really?
**VM = A virtual computer running inside Google's data center**

### The Layer Cake
```
Physical Server (Google's Hardware)
     ↓
Hypervisor (Virtualization Layer)
     ↓
Virtual Machine (Your Instance)
     ↓
Operating System (Linux/Windows)
     ↓
Your Application (Spring Boot, Node, etc.)
```

**You get a full computer — CPU, RAM, disk, network — without owning any hardware.**

---

## VM Building Blocks
### 1. Machine Type (CPU + RAM)
| Type | Specs | Use Case |
|---|---|---|
| `e2-micro` | 0.25 vCPU, 1GB RAM | Free tier, testing |
| `e2-standard-2` | 2 vCPU, 8GB RAM | Small apps |
| `e2-standard-8` | 8 vCPU, 32GB RAM | Production workloads |

### 2. Boot Disk
- **Standard** — cost-effective, HDD-like
- **SSD** — faster, for production

### 3. OS Image
Ubuntu, Debian, CentOS, Windows Server

### 4. Networking
- Internal IP (always assigned)
- External IP (optional — for internet access)

---

## VM Lifecycle: Create → Use → Pay
```
Create → Running (billing starts)
              ↓
         Stop (CPU billing stops, disk still billed)
              ↓
         Delete (all billing stops, data lost)
```

### Critical Billing Insight
| State | CPU Billed? | Disk Billed? |
|---|---|---|
| Running | Yes | Yes |
| Stopped | No | **Yes!** |
| Deleted | No | No |

**Forgetting to stop/delete VMs = the most common billing mistake.**

---

## Hands-On: Create Your First VM

### Via Console
1. Go to **Compute Engine → VM Instances**
2. Click **Create Instance**
3. Configure: Name `my-first-vm`, Region `asia-south1`, Zone `asia-south1-a`, Machine `e2-micro`, OS `Ubuntu`
4. Click **Create**

### Via Command Line
```bash
gcloud compute instances create my-first-vm \
  --zone=asia-south1-a \
  --machine-type=e2-micro \
  --image-family=ubuntu-2204-lts \
  --image-project=ubuntu-os-cloud
```

**Your VM is ready in ~30 seconds.**

---

## Hands-On: SSH & Deploy

### Connect
Click the **SSH** button in Console (browser-based terminal)

### Install Java
```bash
sudo apt update
sudo apt install openjdk-17-jdk -y
java -version
```

### Run Your Spring Boot App
```bash
wget https://your-bucket/app.jar
java -jar app.jar
```

### Open the Firewall
Allow port 8080: **VPC → Firewall → Add Rule → TCP:8080**

### Access It
```
http://<EXTERNAL-IP>:8080
```

---

## When to Use VMs (and When NOT To)

### Use VMs When:
- You need **full OS control** (custom kernel, specific drivers)
- Running **legacy applications** that can't be containerized
- **Custom dependencies** that need specific setup
- **Learning infrastructure** fundamentals

### Avoid VMs When:
- Building **microservices** → use Cloud Run
- Want **auto-scaling** → VMs require manual scaling
- Don't want **maintenance overhead** → VMs need patching

---

## VM vs Cloud Run: The Honest Comparison
| Feature | VM (Compute Engine) | Cloud Run |
|---|---|---|
| Infrastructure mgmt | You manage everything | Google manages it |
| Scaling | Manual (add more VMs) | Automatic (0 to N) |
| Cost model | Always running = always paying | Pay per request |
| Startup time | Minutes | Seconds |
| Best for | Legacy, custom setups | Modern microservices |
| Learning curve | Understand infra deeply | Focus on code |

---

## Real-World Mistakes with VMs
| Mistake | Impact | Prevention |
|---|---|---|
| Forgetting to stop VM | Bills keep running 24/7 | Set reminders, use budget alerts |
| Exposing all ports | Security breach risk | Whitelist only needed ports |
| Using VMs for microservices | Over-managing infra | Use Cloud Run instead |
| No monitoring setup | Blind to issues | Enable Cloud Monitoring |
| Single zone deployment | Downtime on zone failure | Deploy across zones |

---

## Expert Takeaways
- **VMs** = maximum control, minimum automation
- **You manage:** OS, security patches, scaling, monitoring
- **Great for:** Learning cloud fundamentals & running legacy systems
- **Not ideal for:** Modern cloud-native microservices
- **Always remember:** Stop what you're not using — your wallet will thank you

---

## What's Next?
**Next:** Cloud Run — the modern way to deploy. Zero infra, auto-scaling, pay-per-request. This is where cloud gets exciting.
