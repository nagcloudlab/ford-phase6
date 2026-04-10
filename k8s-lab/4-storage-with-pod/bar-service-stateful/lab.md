# Lab: Making `bar-service` Stateful on GKE

> Topic: **Volumes, PersistentVolumes, PersistentVolumeClaims**
> Cluster: **GKE** (Google Kubernetes Engine)
> Service under test: **bar-service** (Spring Boot)

---

## 1. Background — why this lab exists

So far `bar-service` has been **stateless**: every call to `GET /bar` just
returns the pod hostname. Kill the pod → nothing is lost, because there was
nothing to lose.

In the real world, services need to **remember things** across restarts:
uploaded files, logs, audit trails, session data, caches.

In Kubernetes, "remembering things" = **volumes**. A volume is simply a
directory that is mounted into a container; what differs is **where that
directory physically lives** and **how long it survives**.

This lab walks through three very different storage backends, using the
same service, so trainees can _feel_ the difference:

| # | Volume type    | Lives in          | Survives pod delete? | Survives node delete? | Shared across pods? |
|---|----------------|-------------------|----------------------|-----------------------|---------------------|
| 1 | `emptyDir`     | Node's scratch    | ❌ no                 | ❌ no                  | ❌ no                |
| 2 | `hostPath`     | Node's filesystem | ✅ yes                | ❌ no (node-local)     | ❌ only same node    |
| 3 | GCS (CSI fuse) | GCS bucket        | ✅ yes                | ✅ yes                 | ✅ yes (RWX)         |

---

## 2. What we changed in `bar-service`

`bar-service` is now **stateful**. It exposes:

| Method | Path           | Behavior                                           |
|--------|----------------|----------------------------------------------------|
| GET    | `/bar`         | Returns hostname and the configured `DATA_DIR`     |
| POST   | `/bar/notes?msg=hello` | Appends a timestamped line to `notes.log`  |
| GET    | `/bar/notes`   | Returns all saved lines                            |

State is persisted to `${DATA_DIR}/notes.log`. `DATA_DIR` is an env var
(default `/data`). Whatever we mount at `/data` **is** the service's state.

Build & push the new image (tag `v2`):

```bash
cd ~/ford-phase6/k8s-lab/bar-service
docker build -t docker.io/nagabhushanamn/bar-service:v2 .
docker push docker.io/nagabhushanamn/bar-service:v2
```

---

## 3. Prep — connect to your GKE cluster

```bash
gcloud container clusters get-credentials <CLUSTER_NAME> \
  --region <REGION> --project <PROJECT_ID>

kubectl get nodes
cd ~/ford-phase6/k8s-lab/4-storage-with-pod/bar-service-stateful
```

---

## 4. Part 1 — `emptyDir` (ephemeral scratch)

### 4.1 Concept
- Created when the Pod is scheduled to a node.
- Lives as long as the **Pod** does.
- Delete the pod → the volume is wiped.
- Container crash/restart → data is **kept** (same pod).
- Good for: scratch space, caches, sharing files between containers.

### 4.2 Deploy

```bash
kubectl apply -f 01-bar-service-emptydir.yaml
kubectl get pods -l app=bar-emptydir -w
```

### 4.3 Exercise

```bash
# Port-forward so we can curl it
kubectl port-forward svc/bar-service-emptydir 8080:80 &

curl localhost:8080/bar
curl -X POST "localhost:8080/bar/notes?msg=hello-from-emptyDir"
curl -X POST "localhost:8080/bar/notes?msg=second-note"
curl localhost:8080/bar/notes
```

### 4.4 Prove it survives a **container** restart

```bash
POD=$(kubectl get pod -l app=bar-emptydir -o jsonpath='{.items[0].metadata.name}')

# kill the java process inside the container — kubelet restarts it
kubectl exec $POD -- sh -c 'kill 1'
kubectl get pod $POD   # RESTARTS should become 1
curl localhost:8080/bar/notes     # notes are STILL THERE ✅
```

### 4.5 Prove it DOES NOT survive **pod** delete

```bash
kubectl delete pod $POD
# wait for the replacement pod
kubectl get pods -l app=bar-emptydir -w
curl localhost:8080/bar/notes     # EMPTY ❌  — volume is gone with old pod
```

### 4.6 Cleanup

```bash
kubectl delete -f 01-bar-service-emptydir.yaml
```

> **Trainer talking point:** emptyDir is tied to a **Pod**, not a container,
> and not a node. Restart = OK. Reschedule = data lost.

---

## 5. Part 2 — `hostPath` via PV + PVC (node-local persistence)

### 5.1 Concept
- Mounts a directory from the **node's own filesystem** into the pod.
- Data survives pod deletion — it's just sitting on the node's disk.
- **But** if the pod lands on a different node, it's a different disk →
  data looks "lost". In multi-node GKE clusters this is a trap.
- Real use case: DaemonSets collecting node-local logs (`/var/log`),
  metrics agents reading `/proc`, single-node dev clusters.
- We'll use the **PV + PVC** pattern — even with hostPath — to teach the
  proper abstraction: **pod → PVC → PV → storage**.

### 5.2 Deploy

```bash
kubectl apply -f 02-bar-service-hostpath.yaml
kubectl get pv,pvc
kubectl get pods -l app=bar-hostpath -o wide   # note the NODE
```

### 5.3 Exercise

```bash
kubectl port-forward svc/bar-service-hostpath 8081:80 &

curl localhost:8081/bar
curl -X POST "localhost:8081/bar/notes?msg=hello-from-hostPath"
curl localhost:8081/bar/notes
```

### 5.4 Prove it survives a **pod** delete

```bash
POD=$(kubectl get pod -l app=bar-hostpath -o jsonpath='{.items[0].metadata.name}')
kubectl delete pod $POD
kubectl get pods -l app=bar-hostpath -w

# new pod — but IF it lands on the same node, the notes are still there
curl localhost:8081/bar/notes     # ✅ still there (usually)
```

### 5.5 Inspect the node to see the real directory

```bash
NODE=$(kubectl get pod -l app=bar-hostpath -o jsonpath='{.items[0].spec.nodeName}')
echo "data is under /var/lib/bar-service on node $NODE"

# One way to peek — run a debug pod on that node:
kubectl debug node/$NODE -it --image=busybox -- sh
# inside:
#   ls /host/var/lib/bar-service
#   cat /host/var/lib/bar-service/notes.log
```

### 5.6 Demonstrate the "node trap"

Drain the node the pod is on, or cordon it, so the Deployment reschedules:

```bash
kubectl cordon $NODE
kubectl delete pod -l app=bar-hostpath
kubectl get pod -l app=bar-hostpath -o wide     # now on a DIFFERENT node
curl localhost:8081/bar/notes                   # EMPTY ❌

kubectl uncordon $NODE
```

### 5.7 Cleanup

```bash
kubectl delete -f 02-bar-service-hostpath.yaml
# PV is Retain — hostPath dir still exists on the node
```

> **Trainer talking point:** hostPath is persistent **per node**, not per
> cluster. That's why it's almost never the right answer for stateful
> apps — the Pod could wake up on a different node. The point is to
> understand how the PV/PVC/Pod wiring works before we introduce a
> real backend.

---

## 6. Part 3 — GCS bucket via the Cloud Storage FUSE CSI driver

Now the good one. We will mount a **GCS bucket** as a volume. This is
real cluster-wide persistence, native to GCP, and supports RWX.

### 6.1 Concept
- Uses the GKE-managed **Cloud Storage FUSE CSI driver**.
- The driver mounts a bucket as a POSIX-ish filesystem inside the Pod.
- The bucket is the source of truth. Nodes are cattle — scale them, drain
  them, delete them, the data stays.
- `ReadWriteMany` — every replica across every node sees the same files.
- Authenticates using **Workload Identity** (GKE KSA ↔ Google SA).
- Not a database. Object semantics. Great for logs, blobs, ML datasets.

### 6.2 One-time cluster prep (do this once per cluster)

```bash
# Vars
export PROJECT_ID=$(gcloud config get-value project)
export CLUSTER=<CLUSTER_NAME>
export REGION=<REGION>
export BUCKET=ford-phase6-bar-state-$PROJECT_ID
export GSA=bar-gcs-sa
export NS=default
export KSA=bar-gcs-ksa

# 1. Enable the CSI driver on the cluster
gcloud container clusters update $CLUSTER \
  --region $REGION \
  --update-addons GcsFuseCsiDriver=ENABLED

# 2. Ensure Workload Identity is enabled (most GKE clusters already do)
gcloud container clusters describe $CLUSTER --region $REGION \
  --format="value(workloadIdentityConfig.workloadPool)"
# expect: <PROJECT_ID>.svc.id.goog

# 3. Create the bucket
gcloud storage buckets create gs://$BUCKET --location=$REGION

# 4. Create a Google service account and grant it access to the bucket
gcloud iam service-accounts create $GSA
gcloud storage buckets add-iam-policy-binding gs://$BUCKET \
  --member "serviceAccount:$GSA@$PROJECT_ID.iam.gserviceaccount.com" \
  --role "roles/storage.objectUser"

# 5. Bind the Kubernetes SA to the Google SA (Workload Identity)
gcloud iam service-accounts add-iam-policy-binding \
  $GSA@$PROJECT_ID.iam.gserviceaccount.com \
  --role roles/iam.workloadIdentityUser \
  --member "serviceAccount:$PROJECT_ID.svc.id.goog[$NS/$KSA]"
```

### 6.3 Fill in the manifest

Edit `03-bar-service-gcs.yaml` and replace:

- `<BUCKET_NAME>` → value of `$BUCKET`
- `<GSA_EMAIL>`   → `bar-gcs-sa@<PROJECT_ID>.iam.gserviceaccount.com`

Or do it inline:

```bash
sed -e "s|<BUCKET_NAME>|$BUCKET|" \
    -e "s|<GSA_EMAIL>|$GSA@$PROJECT_ID.iam.gserviceaccount.com|" \
    03-bar-service-gcs.yaml | kubectl apply -f -
```

### 6.4 Verify the wiring

```bash
kubectl get pv,pvc
kubectl get pods -l app=bar-gcs -o wide
kubectl describe pod -l app=bar-gcs | grep -A3 gke-gcsfuse
# you should see the 'gke-gcsfuse-sidecar' container injected
```

### 6.5 Exercise — RWX across replicas

```bash
kubectl port-forward svc/bar-service-gcs 8082:80 &

# Write some notes — each request may hit a DIFFERENT pod (round-robin)
for i in 1 2 3 4 5; do
  curl -X POST "localhost:8082/bar/notes?msg=note-$i"
done

# Read them back — all pods see the same file
curl localhost:8082/bar/notes
```

### 6.6 Prove it survives everything

```bash
# Delete every pod at once
kubectl delete pod -l app=bar-gcs
kubectl get pods -l app=bar-gcs -w

# Fresh pods, brand-new containers, possibly different nodes
curl localhost:8082/bar/notes     # ✅ all notes still there
```

Also verify directly in GCS — the file is a real object in the bucket:

```bash
gcloud storage ls gs://$BUCKET/
gcloud storage cat gs://$BUCKET/notes.log
```

### 6.7 Cleanup

```bash
kubectl delete -f 03-bar-service-gcs.yaml
gcloud storage rm --recursive gs://$BUCKET
gcloud storage buckets delete gs://$BUCKET
```

> **Trainer talking point:** notice we deployed 3 replicas, probably on
> different nodes, and they all read/wrote the **same** file. That is the
> super-power of `ReadWriteMany` on object storage — impossible with
> `emptyDir` or `hostPath`.

---

## 7. Wrap-up discussion

Ask trainees to explain, using what they just saw:

1. Why did `emptyDir` data vanish when the pod was deleted, but not when
   the container restarted?
2. Why is `hostPath` dangerous in a multi-node cluster? When IS it a
   good choice?
3. Why can GCS-backed volumes safely run 3+ replicas while hostPath
   cannot?
4. What role does the **PVC** play? Why not mount the PV directly?
5. Where does **Workload Identity** fit in the GCS scenario, and why
   not just bake credentials into the image?

### Cheat sheet

```text
Pod ──mounts──▶ Volume ──backed by──▶ { emptyDir | hostPath | CSI driver }

PersistentVolumeClaim (what the app asks for)
        │  binds to
        ▼
PersistentVolume      (the actual piece of storage)
        │  provided by
        ▼
StorageClass / static definition
```

---

## 8. Files in this folder

```
01-bar-service-emptydir.yaml   # Deployment + Service, emptyDir /data
02-bar-service-hostpath.yaml   # PV + PVC + Deployment + Service, hostPath
03-bar-service-gcs.yaml        # KSA + PV + PVC + Deployment + Service, GCS fuse CSI
lab.md                         # this file
```
