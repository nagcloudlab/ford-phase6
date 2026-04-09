# Lab 02: Project Setup, Billing Alerts & IAM Basics

**Duration:** 30 minutes
**Objective:** Create a dedicated workshop project, set up billing alerts to prevent surprises, and understand IAM fundamentals.

---

## Prerequisites

- Lab 01 completed (GCP account with free trial active)

---

## Part 1: Create a Workshop Project

The default "My First Project" is fine for exploring, but real teams always create **dedicated projects** per application or environment.

### Create

1. Click the **Project Selector** dropdown (top bar)
2. Click **New Project**
3. Enter:
   - **Project name:** `upi-workshop` (keep it short)
   - **Organization:** Leave as default
4. Click **Create**
5. Wait ~10 seconds, then **select** `upi-workshop` from the dropdown

### Verify

- Top bar shows `upi-workshop`
- Note your **Project ID** — it's globally unique and different from the project name

```
Project Name: upi-workshop
Project ID:   upi-workshop-438207  (example — yours will differ)
```

### Why Separate Projects?

| Pattern | What Happens |
|---------|-------------|
| Everything in one project | Access chaos, billing confusion, no isolation |
| Separate project per app/env | Clean isolation, clear billing, proper access |

**Rule:** In production, always have separate projects for `dev`, `staging`, `prod`.

---

## Part 2: Link Billing & Set Alerts

### Link billing account

1. Go to **Navigation Menu > Billing**
2. If prompted, link your free trial billing account to `upi-workshop`
3. Verify: billing page shows `upi-workshop` under linked projects

### Create a budget alert

This is the **single most important thing** beginners skip — and then get surprise bills.

1. Go to **Billing > Budgets & Alerts**
2. Click **Create Budget**
3. Configure:
   - **Name:** `workshop-safety-net`
   - **Projects:** Select `upi-workshop`
   - **Budget amount:** `$10`
   - **Alert thresholds:** `25%`, `50%`, `75%`, `100%`
   - **Notifications:** Email alerts to your Gmail
4. Click **Finish**

### What happens when you hit the budget?

- You get **email alerts** at each threshold
- GCP does **NOT automatically stop** your services (that's why alerts matter)
- You must manually stop/delete resources if needed

---

## Part 3: Enable Required APIs

GCP services need their APIs explicitly enabled before use.

1. Go to **Navigation Menu > APIs & Services > Library**
2. Search and **Enable** each:

| API | Why We Need It |
|-----|---------------|
| **Compute Engine API** | To create VMs (Lab 04) |
| **Cloud Run Admin API** | To deploy containers (Lab 06) |
| **Artifact Registry API** | To store Docker images (Lab 05) |
| **Cloud Build API** | To build images in the cloud (Lab 05) |

### Verify via Console

Go to **APIs & Services > Enabled APIs** — you should see all four listed.

---

## Part 4: IAM — Understand Access Control

### Navigate

Go to **Navigation Menu > IAM & Admin > IAM**

### What you see

A list of members and their roles on this project. Right now it's just you with the **Owner** role.

### The IAM Formula

```
WHO   (user/service account)
  +
WHAT  (role = set of permissions)
  +
WHERE (project/resource)
  =
ACCESS GRANTED
```

### Explore roles (DO NOT add anyone — just browse)

1. Click **+ Grant Access**
2. In the **Role** dropdown, explore:

| Role Type | Example | When to Use |
|-----------|---------|-------------|
| **Basic** | Owner, Editor, Viewer | Almost never in production (too broad) |
| **Predefined** | Cloud Run Admin, Storage Object Viewer | Yes — specific to a service |
| **Custom** | Your own combination | Advanced — for fine-grained control |

3. Click **Cancel** — we're just exploring

### The Classic Mistake

| Bad Practice | Risk |
|-------------|------|
| Give **Owner** role to all 10 developers | Anyone can delete databases, change billing, destroy everything |
| Give **Cloud Run Admin** to developers | They can only manage Cloud Run — nothing else |

**Principle of Least Privilege:** Give the minimum access needed. Nothing more.

---

## Part 5: Service Accounts (Preview)

When your **application** needs to access GCP services (not a human, but code), it uses a **Service Account**.

### View default service account

1. Go to **IAM & Admin > Service Accounts**
2. You'll see a default service account created for the project
3. Format: `PROJECT_NUMBER-compute@developer.gserviceaccount.com`

### Why service accounts matter

```
Human User → logs in with Google account → gets IAM role
Application → uses Service Account → gets IAM role
```

- No hardcoded passwords in code
- Permissions controlled via IAM
- Auditable — you can see what every service account did

> We'll use service accounts when we deploy to Cloud Run — it automatically gets one.

---

## Checkpoint

- [ ] Created `upi-workshop` project and selected it
- [ ] Billing linked with a $10 budget alert
- [ ] Four APIs enabled (Compute Engine, Cloud Run, Artifact Registry, Cloud Build)
- [ ] Understand IAM formula: WHO + WHAT + WHERE
- [ ] Know the difference between Basic roles (broad) and Predefined roles (specific)
- [ ] Seen the default Service Account

---

## Key Takeaways

- **Project = your isolation boundary** — separate billing, separate access, separate resources
- **Budget alerts on Day 1, not after the shock** — 5 minutes of setup saves real money
- **IAM = the security backbone** — never give Owner to everyone
- **Service Accounts = identity for your apps** — no hardcoded credentials, ever
