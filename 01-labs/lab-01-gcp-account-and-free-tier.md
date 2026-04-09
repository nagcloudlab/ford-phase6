# Lab 01: Create a GCP Account & Activate Free Tier

**Duration:** 20 minutes
**Objective:** Create a Google Cloud account, activate the $300 free trial, and take your first look at the GCP Console.

---

## What You Get with Free Tier

| Benefit | Details |
|---------|---------|
| **$300 credit** | Valid for 90 days from activation |
| **No auto-charge** | Google will NOT charge your card after the trial ends |
| **Always Free products** | Some services remain free forever (e2-micro VM, 2M Cloud Run requests/month, etc.) |

> Your card is only for identity verification. Google explicitly states: **"We won't charge you until you manually upgrade."**

---

## Step 1: Create a Google Account (Skip if You Have One)

1. Go to [https://accounts.google.com/signup](https://accounts.google.com/signup)
2. Fill in your details and create an account

---

## Step 2: Activate GCP Free Trial

1. Open [https://cloud.google.com/free](https://cloud.google.com/free)
2. Click **Get started for free**
3. Sign in with your Google account
4. Fill in:
   - **Country:** India (or your country)
   - **Account type:** Individual
   - **Payment method:** Add a debit/credit card (for verification only)
5. Accept terms and click **Start my free trial**

### After Activation

You should see the **GCP Console Dashboard** with:
- A banner showing your **$300 credit** and remaining days
- Your default project (named "My First Project")

---

## Step 3: Console Tour

Take 5 minutes to explore:

| Area | Where | What You See |
|------|-------|-------------|
| **Navigation Menu** | Hamburger icon (top-left) | All GCP services organized by category |
| **Project Selector** | Top bar, next to "Google Cloud" | Switch between projects |
| **Cloud Shell** | Terminal icon (top-right) | Free browser-based terminal |
| **Search Bar** | Top center | Search any service, doc, or setting |
| **Notifications** | Bell icon (top-right) | Deployment status, alerts |

### Try the Search Bar

Type these and observe what comes up:
- `Cloud Run` — takes you to the Cloud Run service page
- `IAM` — takes you to Identity & Access Management
- `billing` — takes you to billing dashboard

---

## Step 4: Verify Your Free Trial Status

1. Go to **Navigation Menu > Billing**
2. You should see:
   - **Billing account** linked to your project
   - **Credits remaining:** $300.00
   - **Trial days remaining:** 90

---

## Checkpoint

- [ ] GCP account created
- [ ] Free trial activated ($300 credit visible)
- [ ] Console dashboard accessible
- [ ] Know where the Navigation Menu, Project Selector, Cloud Shell, and Search Bar are

---

## Important Notes

- **Do NOT upgrade to a paid account** during the workshop — free tier is enough
- If you already have a GCP account with an expired trial, create a **new project** under the same account — many services still have an Always Free tier
- **Bookmark** the Console: [https://console.cloud.google.com](https://console.cloud.google.com)
