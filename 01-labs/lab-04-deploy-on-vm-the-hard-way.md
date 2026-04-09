# Lab 04: Deploy on a VM — The Hard Way

**Duration:** 45 minutes
**Objective:** Deploy the UPI Transfer Service on a Compute Engine VM manually. Experience firsthand why managed services like Cloud Run exist.

---

## Prerequisites

- Lab 03 completed (app tested locally in Cloud Shell)

---

## Why Are We Doing This?

You could skip VMs and go straight to Cloud Run. But without experiencing the **pain of manual server management**, you won't truly appreciate what Cloud Run automates for you.

**This lab exists to teach you what you're being saved from.**

---

## Part 1: Create a VM Instance

```bash
gcloud compute instances create upi-vm \
  --zone=asia-south1-a \
  --machine-type=e2-small \
  --image-family=ubuntu-2204-lts \
  --image-project=ubuntu-os-cloud \
  --boot-disk-size=20GB \
  --tags=http-server
```

### What you just configured

| Setting | Value | Why |
|---------|-------|-----|
| Machine type | `e2-small` (2 vCPU, 2GB RAM) | Enough for a Spring Boot app |
| OS | Ubuntu 22.04 LTS | Familiar, well-supported |
| Disk | 20 GB | Enough for OS + JDK + app |
| Tags | `http-server` | Used by firewall rules |

### Wait ~30 seconds, then verify

```bash
gcloud compute instances list
```

You should see your VM with a **RUNNING** status and an **EXTERNAL_IP**.

```bash
# Save the external IP for later
export VM_IP=$(gcloud compute instances describe upi-vm \
  --zone=asia-south1-a --format='get(networkInterfaces[0].accessConfigs[0].natIP)')
echo "VM IP: $VM_IP"
```

---

## Part 2: SSH into the VM

```bash
gcloud compute ssh upi-vm --zone=asia-south1-a
```

> First time: it will generate SSH keys. Accept the defaults and press Enter for passphrase.

You're now **inside the VM**. Notice the prompt changed:

```
your-username@upi-vm:~$
```

---

## Part 3: Install Java (Manually)

The VM is a **blank Linux machine**. Nothing is pre-installed for your app.

```bash
# Update package list
sudo apt update

# Install Java 17
sudo apt install -y openjdk-17-jdk

# Verify
java -version
```

> Notice: this is a step you have to do **every time** you create a new VM or rebuild the server. Cloud Run handles this for you.

---

## Part 4: Copy the App to the VM

**Open a new Cloud Shell tab** (not inside the VM) and copy the built jar:

```bash
# Build first if needed
cd ~/upi-transfer-service
./mvnw clean package -DskipTests

# Copy the jar to the VM
gcloud compute scp target/*.jar upi-vm:~/app.jar --zone=asia-south1-a

# Also copy the data.sql for reference
gcloud compute scp src/main/resources/data.sql upi-vm:~/data.sql --zone=asia-south1-a
```

---

## Part 5: Run the App on the VM

**Go back to the SSH tab** (inside the VM):

```bash
# Verify the jar is there
ls -la ~/app.jar

# Run the app
java -jar ~/app.jar
```

Spring Boot starts. The app is running on the VM on port 8080.

### Try to access it from your browser

Open a new browser tab and go to:

```
http://<VM_EXTERNAL_IP>:8080/api/balance/nag@upi
```

**It doesn't work.** Connection times out.

### Why?

The VM's **firewall blocks all incoming traffic by default**. You need to explicitly open port 8080.

---

## Part 6: Open the Firewall (Manually)

**In your Cloud Shell tab** (not the SSH session):

```bash
gcloud compute firewall-rules create allow-upi-app \
  --direction=INGRESS \
  --priority=1000 \
  --network=default \
  --action=ALLOW \
  --rules=tcp:8080 \
  --source-ranges=0.0.0.0/0 \
  --target-tags=http-server
```

### Now try again

```
http://<VM_EXTERNAL_IP>:8080/api/balance/nag@upi
```

It works! You should see the JSON response with Nagendra's balance.

### Test the other endpoints

```bash
# From Cloud Shell (not SSH)
export VM_IP=$(gcloud compute instances describe upi-vm \
  --zone=asia-south1-a --format='get(networkInterfaces[0].accessConfigs[0].natIP)')

# Balance
curl -s http://$VM_IP:8080/api/balance/nag@upi | python3 -m json.tool

# Transfer
curl -s -X POST http://$VM_IP:8080/api/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderUpiId": "nag@upi",
    "receiverUpiId": "priya@upi",
    "amount": 200,
    "remark": "vm test"
  }' | python3 -m json.tool

# Swagger UI — open in browser
echo "http://$VM_IP:8080/swagger-ui.html"
```

---

## Part 7: Feel the Pain

Now that the app is running, let's count everything you had to do manually:

| Step | What You Did | Cloud Run Does This? |
|------|-------------|---------------------|
| 1 | Create a VM | Auto |
| 2 | SSH into it | Not needed |
| 3 | Install Java | Built into container |
| 4 | Copy the jar | Image pull |
| 5 | Open firewall port | Auto |
| 6 | Run the app manually | Auto |
| 7 | No HTTPS | Free SSL certificate |
| 8 | No auto-scaling | Auto (0 to N) |
| 9 | VM runs 24/7 (paying even when idle) | Scale to zero |
| 10 | If VM crashes, app is down | Auto-restart |
| 11 | OS patching is your job | Google handles it |

### And we haven't even addressed:

- What if you need to deploy to **multiple servers**?
- What if one server goes **down at 3 AM**?
- What about **load balancing** across servers?
- What about **rolling updates** without downtime?

**Every item above is a manual task with VMs. Every item is automatic with Cloud Run.**

---

## Part 8: Stop the VM (Save Money!)

The VM is billing you right now, even if nobody is using the app.

### Stop the SSH session

In the SSH tab, press **Ctrl+C** to stop the app, then:

```bash
exit
```

### Stop the VM (from Cloud Shell)

```bash
# Stop the VM (stops CPU billing, disk still billed)
gcloud compute instances stop upi-vm --zone=asia-south1-a --quiet

# Verify it's stopped
gcloud compute instances list
```

### Delete the VM and firewall rule (full cleanup)

```bash
# Delete the VM
gcloud compute instances delete upi-vm --zone=asia-south1-a --quiet

# Delete the firewall rule
gcloud compute firewall-rules delete allow-upi-app --quiet
```

---

## Checkpoint

- [ ] Created a VM, SSHed into it, installed Java manually
- [ ] Copied the jar and ran the app on the VM
- [ ] Experienced the firewall block and fixed it manually
- [ ] Accessed the app from the browser via external IP
- [ ] Understood the 11 things you had to do manually
- [ ] Cleaned up the VM and firewall rule

---

## Key Takeaways

- **VMs give you full control** — but full control means full responsibility
- **The firewall block is a lesson** — nothing is open by default in GCP (good for security, painful for setup)
- **VMs bill 24/7** — even when nobody is using your app, the meter is running
- **No HTTPS, no auto-scaling, no auto-restart** — all manual with VMs
- **This is why Cloud Run exists** — everything we did in 45 minutes of manual work, Cloud Run does in one command
- **Next lab:** We containerize the app and deploy it the modern way
