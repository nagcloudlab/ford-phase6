# GCP Projects, IAM & Billing
## The Foundation Where 70% of Mistakes Happen

---

## Why This Section Matters Most
**Hard truth:** Most cloud disasters aren't caused by bad code.
They're caused by:
- Wrong access given to wrong people
- No billing alerts set up
- Everything dumped into one project

**Master this section = avoid 70% of real-world cloud mistakes.**

---

## GCP Resource Hierarchy — The Org Chart of Cloud
```
Organization (Your Company)
   └── Folders (Departments)
         └── Projects (Core Unit)
               └── Resources (VM, DB, Storage)
```

**Think of it like:**
- Organization = Company HQ
- Folder = Department (Engineering, Finance)
- Project = Team workspace
- Resources = The actual tools & services

---

## Projects: The Most Important Concept
**Everything in GCP lives inside a Project.**
- Compute Engine VMs
- Cloud Run services
- Storage buckets
- Databases

**Critical rule:** If a project is deleted → EVERYTHING inside is gone.

---

## Real-World Example: NPCI-Style Setup
```
Organization: npci.org
   └── Folder: payments
         ├── Project: upi-prod
         ├── Project: upi-dev
         └── Project: upi-test
```

### The Beginner Mistake
Putting everything in ONE project → access chaos, billing confusion, no isolation.

### The Right Pattern
Separate by environment: `myapp-dev`, `myapp-test`, `myapp-prod`

**Golden rule:** Always separate PROD from NON-PROD.

---

## IAM: Who Can Do What, Where?
**IAM = Identity & Access Management**

Three components define every permission:

| Component | Question | Example |
|---|---|---|
| **Who** | Which user/service? | dev@company.com |
| **What** | Which role? | Viewer |
| **Where** | Which resource? | myapp-prod project |

**Result:** dev@company.com can only VIEW resources in myapp-prod.

---

## IAM Roles: From Broad to Precise

### Basic Roles (Too Broad for Production)
| Role | Access Level |
|---|---|
| Owner | Full control + billing |
| Editor | Create & modify resources |
| Viewer | Read-only access |

### Predefined Roles (Use These!)
- Compute Admin — manage VMs only
- Storage Admin — manage buckets only
- Cloud Run Admin — manage Cloud Run only

**Principle of Least Privilege:** Give the minimum access needed. Nothing more.

---

## The Classic IAM Disaster
**Bad:** Give "Owner" role to all 20 developers
- Anyone can delete production databases
- Anyone can rack up massive bills
- Zero accountability

**Good:** Give specific roles
- Developer → Cloud Run Admin + Logs Viewer
- DBA → Cloud SQL Admin
- Intern → Viewer only

---

## Service Accounts: Identity for Apps
**Problem:** Your Spring Boot app needs to access Cloud Storage. How?

**Wrong way:** Hardcode username/password in code

**Right way:** Use a Service Account
```
App → Service Account → IAM Role → Resource
```
- Secure (no hardcoded credentials)
- IAM-controlled access
- Auditable

**Example:** `payment-service@project.iam.gserviceaccount.com` with Storage Admin role.

---

## Billing: Where Money Meets Architecture

**Rule #1:** Billing is linked to PROJECT.
**Rule #2:** No billing account → most services won't work.

### Real Risks
| Scenario | Consequence |
|---|---|
| No budget alerts | Surprise bill of ₹50,000 |
| Forgot to stop a VM | Charges keep running 24/7 |
| No resource cleanup | Zombie resources eating money |

### Best Practices
- Set budget alerts at 50%, 80%, 100%
- Stop/delete unused resources daily
- Use free tier wisely during learning

---

## Expert Takeaways
- **Project** = isolation boundary + billing boundary
- **IAM** = the security backbone of all GCP
- **Service Accounts** = mandatory for every app
- **Least privilege** = the #1 security principle
- **Billing control** = an architecture responsibility, not an afterthought

---

## What's Next?
**Next:** GCP Global Infrastructure — Regions, Zones, and why your architecture decisions start here.
