# Cloud Storage: Object Storage Deep Dive
## Store Anything. Scale Infinitely. Pay Smartly.

---

## The File Storage Problem
Your app needs to store:
- User profile pictures
- PDF invoices
- Video uploads
- Log files & backups

**Where do you put them?**
- Not in the database (slow, expensive, wrong tool)
- Not on VM disk (not scalable, lost if VM dies)
- **In Cloud Storage — built for exactly this**

---

## What is Cloud Storage?
**Object storage service** — store any file, any size, access from anywhere.

### Key Concept: Buckets & Objects
```
Bucket (container)
   ├── profile1.png
   ├── profile2.jpg
   └── docs/
        └── resume.pdf
```

- **Bucket** = A globally unique container (like a folder in the sky)
- **Object** = Any file stored inside a bucket

**Not a file system.** No folders, no hierarchy — just flat key-value storage with path-like names.

---

## Object Storage vs File System vs Database
| Feature | Cloud Storage | VM Disk | Database |
|---|---|---|---|
| What to store | Files, images, videos | OS files | Structured data |
| Scalability | Unlimited | Limited by disk size | Limited by instance |
| Cost | Very cheap | Medium | Expensive for files |
| Access | Via URL/API anywhere | Only from that VM | Via queries |

### Golden Rule
- **Files & media** → Cloud Storage
- **Structured data** → Database
- **Never store images in your database**

---

## Buckets: The Container for Everything

### Properties
- **Globally unique name** (like domain names — no duplicates worldwide)
- **Region-specific** — choose where data physically lives
- **Access controlled** — IAM-based permissions

### Bucket Location Types
| Type | What | Best For |
|---|---|---|
| Regional | One region (e.g., Mumbai) | Low latency, local users |
| Dual-region | Two specific regions | Redundancy + performance |
| Multi-regional | Spread across continent | Global apps, max availability |

---

## Storage Classes: The Cost Lever
**Not all data is accessed equally. Pay accordingly.**

| Class | Access Pattern | Cost | Example |
|---|---|---|---|
| **Standard** | Frequently accessed | Highest | User profile images |
| **Nearline** | ~Once a month | Medium | Monthly reports |
| **Coldline** | ~Once a quarter | Low | Compliance archives |
| **Archive** | ~Once a year | Lowest | 7-year audit logs |

### Cost Savings Example
Moving 1TB of old logs from Standard → Coldline can save **~60% on storage costs.**

---

## Access Control: Who Can See Your Files?

### IAM-Based (Recommended)
Role-based access at bucket level
- `Storage Admin` — full control
- `Storage Object Viewer` — read-only
- `Storage Object Creator` — upload only

### Public vs Private
| Mode | Access | URL |
|---|---|---|
| Private (Default) | Only authorized users | Signed URLs for temp access |
| Public | Anyone on internet | `https://storage.googleapis.com/bucket/file` |

**Best Practice:** Default everything to PRIVATE. Open up selectively.

---

## Hands-On: Working with Buckets

### Create a Bucket
```bash
gcloud storage buckets create gs://myapp-user-images \
  --location=asia-south1
```

### Upload a File
```bash
gcloud storage cp photo.jpg gs://myapp-user-images/
```

### List Files
```bash
gcloud storage ls gs://myapp-user-images/
```

### Download a File
```bash
gcloud storage cp gs://myapp-user-images/photo.jpg ./local-photo.jpg
```

---

## Spring Boot Integration
### Use Case: Upload User Profile Image

**1. Add Dependency**
```xml
<dependency>
  <groupId>com.google.cloud</groupId>
  <artifactId>google-cloud-storage</artifactId>
</dependency>
```

**2. Upload Code**
```java
Storage storage = StorageOptions.getDefaultInstance().getService();
BlobId blobId = BlobId.of("myapp-bucket", "profile.png");
BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
storage.create(blobInfo, fileBytes);
```

**3. Auth Flow**
```
App → Service Account → IAM Role → Bucket
```
No hardcoded credentials. Secure by design.

---

## Lifecycle Rules: Automate Cost Savings
**Automatically manage files based on age:**

| Rule | Action |
|---|---|
| After 30 days | Move Standard → Nearline |
| After 90 days | Move Nearline → Coldline |
| After 365 days | Delete automatically |

**Set it once, save money forever — without manual cleanup.**

---

## Real-World Mistakes
| Mistake | Impact | Fix |
|---|---|---|
| Making bucket public by default | Data leak, security breach | Always default to private |
| Using Standard for everything | Overpaying for cold data | Use appropriate storage classes |
| Storing files in database | Slow queries, bloated DB | Use Cloud Storage for files |
| No lifecycle rules | Storage costs grow forever | Set auto-transition rules |
| Not using signed URLs | Either too open or too locked | Use signed URLs for temp access |

---

## Expert Takeaways
- **Cloud Storage = default choice** for any file storage
- **Buckets are global** — plan naming carefully
- **Storage class = your cost optimization lever** — use it wisely
- **IAM controls access** — keep everything private by default
- **Always separate:** Files → Storage, Data → Database

---

## What's Next?
**Next:** Cloud SQL — managed relational databases. Transactions, Spring Boot integration, and designing for systems like UPI.
