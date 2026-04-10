
### install kubectl

```bash
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl
sudo mv kubectl /usr/local/bin/kubectl
kubectl version --client
kubectl config get-clusters
kubectl config get-users
kubectl config get-contexts

```

### install kind

```bash
curl -Lo ./kind https://kind.sigs.k8s.io/dl/latest/kind-linux-amd64
chmod +x ./kind
sudo mv ./kind /usr/local/bin/kind
kind version
```


### create k8s cluster
```bash
kind create cluster --image kindest/node:v1.30.0 --config kind-cluster.yaml --name kind-cluster
kind get clusters
kubectl get nodes
```


### delete k8s cluster

```bash
kind delete cluster --name kind-cluster
```

### get all k8s resources

```bash
kubectl api-versions
kubectl api-resources
```

### create a namespace

```bash
kubectl create namespace ford
kubectl get namespaces
```

### switch to a namespace

```bash
kubectl config set-context --current --namespace=ford
```

