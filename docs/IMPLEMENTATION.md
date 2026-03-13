# Universal Messaging Helm Chart - Implementation Guide

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Quick Start](#quick-start)
3. [Azure Infrastructure Setup](#azure-infrastructure-setup)
4. [Deployment Scenarios](#deployment-scenarios)
5. [Active-Passive Deployment](#active-passive-deployment)
6. [Active-Active Deployment](#active-active-deployment)
7. [MSR Integration Configuration](#msr-integration-configuration)
8. [Cluster Management](#cluster-management)
9. [Troubleshooting](#troubleshooting)
10. [Upgrade Procedures](#upgrade-procedures)
11. [Backup and Recovery](#backup-and-recovery)

---

## Prerequisites

### Required Tools

| Tool | Minimum Version | Installation |
|------|-----------------|--------------|
| Azure CLI | 2.50+ | `winget install Microsoft.AzureCLI` |
| kubectl | 1.28+ | `az aks install-cli` |
| Helm | 3.12+ | `winget install Helm.Helm` |

### Azure Subscription Requirements

- Active Azure subscription with Contributor access
- AKS cluster deployed
- Azure Container Registry with UM image

### Network Requirements

| Source | Destination | Port | Purpose |
|--------|-------------|------|---------|
| MSR Pods | UM Pods | 9000 | JMS/NSP connections |
| UM Pods | UM Pods | 9000 | Cluster synchronization |
| Prometheus | UM Pods | 9200 | Metrics collection |

---

## Quick Start

### 1. Clone and Configure

```bash
# Clone the repository
git clone https://github.com/your-org/um-helm.git
cd um-helm

# Login to Azure
az login
az account set --subscription "Your-Subscription-Name"

# Get AKS credentials
az aks get-credentials --resource-group webmethods-rg --name webmethods-aks
```

### 2. Create Namespace

```bash
kubectl create namespace <namespace>
```

> **Namespace flexibility:** The same values files work in any namespace. Just change the `-n` flag. No `global.namespace` needed.

### 3. Choose Deployment Scenario

| Scenario | Values File(s) | Use Case |
|----------|----------------|----------|
| Business Data | `values-business.yaml` | Consistency, auto-failover |
| Log Data | `values-logs-a/b/c.yaml` | High throughput |

---

## Azure Infrastructure Setup

### Create AKS Cluster (if not exists)

```bash
# Variables
RESOURCE_GROUP="webmethods-rg"
AKS_NAME="webmethods-aks"
LOCATION="eastus"

# Create resource group
az group create --name $RESOURCE_GROUP --location $LOCATION

# Create AKS cluster
az aks create \
  --resource-group $RESOURCE_GROUP \
  --name $AKS_NAME \
  --node-count 3 \
  --node-vm-size Standard_D4s_v5 \
  --enable-managed-identity \
  --generate-ssh-keys

# Get credentials
az aks get-credentials --resource-group $RESOURCE_GROUP --name $AKS_NAME
```

### Container Registry Setup

```bash
ACR_NAME="webmethodsacr"

# Login to ACR
az acr login --name $ACR_NAME

# Tag and push UM image
docker tag universalmessaging-server:11.1.2 ${ACR_NAME}.azurecr.io/webmethods/universalmessaging-server:11.1.2
docker push ${ACR_NAME}.azurecr.io/webmethods/universalmessaging-server:11.1.2

# Attach ACR to AKS
az aks update \
  --resource-group webmethods-rg \
  --name webmethods-aks \
  --attach-acr $ACR_NAME
```

---

## Deployment Scenarios

### Scenario 1: Business Data (Active-Passive)

Single cluster with 3 replicas. Use for transactional data requiring consistency.

```
┌─────────────────────────────────┐
│   Active-Passive Cluster        │
│                                 │
│  ┌──────┐ ┌──────┐ ┌──────┐    │
│  │Master│ │Slave │ │Slave │    │
│  │  -0  │ │  -1  │ │  -2  │    │
│  └──────┘ └──────┘ └──────┘    │
│                                 │
│  Connection URL (short DNS):    │
│  nsp://um-biz:9000              │
└─────────────────────────────────┘
```

### Scenario 2: Log Data (Active-Active)

Three independent clusters with combined load balancer. Use for high-throughput logs.

```
┌───────────────────────────────────────────────────────┐
│   Active-Active Multi-Cluster                         │
│                                                       │
│  ┌──────┐   ┌──────┐   ┌──────┐                      │
│  │logs-a│   │logs-b│   │logs-c│                      │
│  │Master│   │Master│   │Master│                      │
│  └──┬───┘   └──┬───┘   └──┬───┘                      │
│     └──────────┼──────────┘                          │
│                │                                      │
│       ┌────────▼────────┐                            │
│       │ um-logs-combined│ ◄─── Publisher URL         │
│       └─────────────────┘                            │
│                                                       │
│  Consumer URLs (connect to all):                     │
│  - nsp://um-logs-a:9000                              │
│  - nsp://um-logs-b:9000                              │
│  - nsp://um-logs-c:9000                              │
└───────────────────────────────────────────────────────┘
```

---

## Active-Passive Deployment

### Deploy Business Data Cluster

```bash
# Deploy the cluster
helm upgrade --install um-biz . -n <namespace> -f values-business.yaml

# Wait for pods to be ready
kubectl wait --for=condition=ready pod -l app=um-biz -n <namespace> --timeout=300s

# Verify cluster
kubectl get pods -n <namespace> -l app=um-biz
```

### Verify Cluster Formation

```bash
# Check cluster state
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ClusterState -rname="nsp://localhost:9000"

# Expected output shows 1 Master + 2 Slaves
```

### Verify Asset Creation

```bash
# List created queues and channels
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ListChannels -rname="nsp://localhost:9000"

# List JNDI connection factories
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ViewConnectionFactory -rname="nsp://localhost:9000" -factoryname="SmPubConnectionFactory"

# Verify JNDI entries exist in realm XML
kubectl exec um-biz-0 -n <namespace> -- bash -c \
  "runUMTool.sh ExportRealmXML -rname='nsp://localhost:9000' -filename='/tmp/realm.xml' -exportall=true && \
   grep 'alias.reference.classname.*QueueImpl\|alias.reference.classname.*TopicImpl' /tmp/realm.xml"

# Check init job logs for JNDI creation
kubectl logs -l component=cluster-init -n <namespace> | grep -A 2 "JNDI"
```

### Connection URL

```
nsp://um-biz:9000
```

---

## Active-Active Deployment

### Step 1: Deploy All Clusters Without Hooks

Deploy all three clusters without init hooks first to avoid timeout:

```bash
# Deploy cluster A (creates combined service)
helm upgrade --install um-logs-a . -n <namespace> -f values-logs-a.yaml --no-hooks

# Deploy cluster B
helm upgrade --install um-logs-b . -n <namespace> -f values-logs-b.yaml --no-hooks

# Deploy cluster C
helm upgrade --install um-logs-c . -n <namespace> -f values-logs-c.yaml --no-hooks
```

### Step 2: Wait for Pods

```bash
# Wait for all log cluster pods to be ready
kubectl wait --for=condition=ready pod -l component=messaging,data-type=logs -n <namespace> --timeout=300s

# Verify pods
kubectl get pods -n <namespace> -l data-type=logs
```

### Step 3: Upgrade to Run Init Hooks

```bash
# Run init hooks for each cluster
helm upgrade um-logs-a . -n <namespace> -f values-logs-a.yaml
helm upgrade um-logs-b . -n <namespace> -f values-logs-b.yaml
helm upgrade um-logs-c . -n <namespace> -f values-logs-c.yaml
```

### Step 4: Verify Deployment

```bash
# Check all services
kubectl get svc -n <namespace> | grep um-logs

# Expected services:
# - um-logs-a          (ClusterIP)
# - um-logs-a-headless (Headless)
# - um-logs-b          (ClusterIP)
# - um-logs-b-headless (Headless)
# - um-logs-c          (ClusterIP)
# - um-logs-c-headless (Headless)
# - um-logs-combined   (ClusterIP - load balancer)

# Check combined service endpoints
kubectl get endpoints um-logs-combined -n <namespace>
```

### Connection URLs

| Purpose | URL |
|---------|-----|
| **Publisher** | `nsp://um-logs-combined:9000` |
| **Consumer A** | `nsp://um-logs-a:9000` |
| **Consumer B** | `nsp://um-logs-b:9000` |
| **Consumer C** | `nsp://um-logs-c:9000` |

---

## MSR Integration Configuration

### Active-Passive (Business Data)

```yaml
# In MSR values.yaml
um:
  enabled: true
  connectionAlias: "IS_UM_BUSINESS"
  url: "nsp://um-biz:9000"
  user: "Administrator"
  useCSQ: "true"        # Enable Client Store Queue
  csqSize: "-1"         # Unlimited
  csqDrainInOrder: "true"
```

### Active-Active (Log Data)

**Publisher MSR:**
```yaml
um:
  enabled: true
  connectionAlias: "IS_UM_LOGS"
  url: "nsp://um-logs-combined:9000"
```

**Consumer MSR (must connect to all clusters):**
```yaml
um:
  connections:
    - alias: "logs-a"
      url: "nsp://um-logs-a:9000"
    - alias: "logs-b"
      url: "nsp://um-logs-b:9000"
    - alias: "logs-c"
      url: "nsp://um-logs-c:9000"
```

### Verify MSR-UM Connection

```bash
# Test connectivity from MSR pod
kubectl exec -it wm-msr-0 -n <namespace> -- nc -zv um-biz 9000

# For Active-Active
kubectl exec -it wm-msr-0 -n <namespace> -- nc -zv um-logs-combined 9000
```

---

## Cluster Management

### View Cluster Status

```bash
# Active-Passive cluster state
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ClusterState -rname="nsp://localhost:9000"

# List all channels and queues
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ListChannels -rname="nsp://localhost:9000"
```

### Scale Active-Passive Cluster

```bash
# Scale up (cluster membership is auto-managed)
kubectl scale statefulset um-biz --replicas=5 -n <namespace>

# Scale down
kubectl scale statefulset um-biz --replicas=3 -n <namespace>
```

### Rolling Restart

```bash
# Active-Passive
kubectl rollout restart statefulset/um-biz -n <namespace>
kubectl rollout status statefulset/um-biz -n <namespace>

# Active-Active (restart each cluster)
kubectl rollout restart statefulset/um-logs-a -n <namespace>
kubectl rollout restart statefulset/um-logs-b -n <namespace>
kubectl rollout restart statefulset/um-logs-c -n <namespace>
```

### View Logs

```bash
# Single pod
kubectl logs um-biz-0 -n <namespace> -f

# Init job logs
kubectl logs -l component=cluster-init -n <namespace>

# Inter-realm init job logs
kubectl logs -l component=inter-realm-init -n <namespace>
```

---

## Troubleshooting

### Pod Stuck in Pending

**Check node resources:**
```bash
kubectl describe nodes | grep -A 5 "Allocated resources"
kubectl top nodes
```

**Solution - Scale AKS:**
```bash
az aks scale \
  --resource-group webmethods-rg \
  --name webmethods-aks \
  --node-count 4
```

### Init Job Timeout

**Problem:** Init job times out waiting for remote clusters.

**Solution:** Deploy with `--no-hooks`, wait for pods, then upgrade:
```bash
# Deploy without hooks
helm upgrade --install um-logs-a . -n <namespace> -f values-logs-a.yaml --no-hooks

# Wait for pod
kubectl wait --for=condition=ready pod -l app=um-logs-a -n <namespace> --timeout=300s

# Run hooks
helm upgrade um-logs-a . -n <namespace> -f values-logs-a.yaml
```

### Queue/Channel/Connection Factory Not Created

**Check init job logs:**
```bash
kubectl logs -l component=cluster-init -n <namespace>

# For Active-Active
kubectl logs -l component=inter-realm-init -n <namespace>
```

**Common causes:**
1. Pod not ready when job ran
2. Invalid asset name (spaces or special characters)
3. Asset already exists (check for "already exists" in logs — this is harmless for native-only assets)
4. For connection factories: invalid factory type (must be `default`, `queue`, `topic`, or `xa`)
5. For JNDI queues/topics: see dedicated troubleshooting section below

**Verify connection factories manually:**
```bash
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ListConnectionFactories -rname="nsp://localhost:9000"
```

### JNDI Queues/Topics Not Appearing

**Symptoms:** Native queues and channels are visible in UM Enterprise Manager, but the JNDI namespace is empty — JMS clients cannot find queues/topics via JNDI lookup.

**Diagnose — Check init job logs:**
```bash
kubectl logs -n <namespace> job/<release>-cluster-init-<revision>
```

Look for these log lines:
- `"=== Creating JNDI Queues (native + JNDI registration) ==="` — confirms JNDI queue section executed
- `"=== Creating JNDI Topics (native + JNDI registration) ==="` — confirms JNDI topic section executed
- `"JNDI queue <name> created successfully (native + JNDI)"` — confirms individual queue registration
- `"JNDI topic <name> created successfully (native + JNDI)"` — confirms individual topic registration

**Common causes:**

| Cause | Log Indicator | Fix |
|-------|---------------|-----|
| JNDI section not in values file | No `"=== Creating JNDI"` lines at all | Add `jndiQueues` and `jndiTopics` sections under `um:` in your values file |
| Wrong YAML indentation | No `"=== Creating JNDI"` lines at all | Ensure `jndiQueues` and `jndiTopics` are indented under `um:` at same level as `queues:` |
| Pre-existing native assets | `"Channel already exists"` errors | Delete existing native queues/channels first, then re-deploy (see migration steps below) |

**Migration from non-JNDI to JNDI deployment:**

If your UM deployment already has native queues/channels that were created without JNDI, `CreateJMSQueue`/`CreateJMSTopic` cannot retroactively add JNDI entries — they fail because the native asset already exists. You must delete and recreate:

```bash
# 1. Delete existing native queues
kubectl exec -n <namespace> <pod-name> -- \
  runUMTool.sh DeleteQueue -rname="nsp://localhost:9000" -queuename="<queue-name>"

# 2. Delete existing native channels (topics)
kubectl exec -n <namespace> <pod-name> -- \
  runUMTool.sh DeleteChannel -rname="nsp://localhost:9000" -channelname="<channel-name>"

# 3. Re-deploy to trigger the init job which will recreate with JNDI
helm upgrade <release> . -n <namespace> -f <values-file>
```

> **Warning:** Deleting queues/channels will remove any pending messages. Only do this when the queues are empty or in a dev/test environment.

**Verify JNDI registration after fix:**
```bash
# Export realm XML and check for JNDI entries
kubectl exec -n <namespace> <pod-name> -- bash -c \
  "runUMTool.sh ExportRealmXML -rname='nsp://localhost:9000' -filename='/tmp/realm.xml' -exportall=true && \
   grep 'alias.*value.*<queue-name>' /tmp/realm.xml"

# Should show entries like:
# <EventProp name="alias" type="String" value="KPILog_Q"/>
# <EventProp name="alias.reference.classname" type="String" value="com.pcbsys.nirvana.nJMS.QueueImpl"/>
```

### Health Probe Failing

**Check health endpoint:**
```bash
kubectl exec um-biz-0 -n <namespace> -- curl -v http://localhost:9000/health/
```

**Solution - Increase probe timeouts:**
```yaml
healthProbes:
  startup:
    initialDelaySeconds: 180
    failureThreshold: 30
  liveness:
    initialDelaySeconds: 240
    timeoutSeconds: 60
```

### MSR Cannot Connect to UM

**Test connectivity:**
```bash
# From MSR pod
kubectl exec -it wm-msr-0 -n <namespace> -- nc -zv um-biz 9000
```

**Check UM service:**
```bash
kubectl get svc um-biz -n <namespace>
kubectl get endpoints um-biz -n <namespace>
```

**Check DNS:**
```bash
kubectl exec -it wm-msr-0 -n <namespace> -- nslookup um-biz
```

### Combined Service Not Working (Active-Active)

**Check pod labels:**
```bash
kubectl get pods -n <namespace> -l data-type=logs --show-labels
```

**Verify selector:**
```bash
kubectl describe svc um-logs-combined -n <namespace>
```

All log cluster pods must have these labels:
```yaml
podLabels:
  component: messaging
  tier: middleware
  data-type: logs
```

---

## Upgrade Procedures

### Rolling Update

```bash
# Update image tag
helm upgrade um-biz . -n <namespace> \
  -f values-business.yaml \
  --set image.tag="11.1.3"

# Monitor rollout
kubectl rollout status statefulset/um-biz -n <namespace>
```

### Staged Rollout (Production)

```bash
# 1. Update one pod at a time using partition
kubectl patch statefulset um-biz -n <namespace> \
  -p '{"spec":{"updateStrategy":{"type":"RollingUpdate","rollingUpdate":{"partition":2}}}}'

# 2. Update first pod
helm upgrade um-biz . -n <namespace> -f values-business.yaml --set image.tag="11.1.3"

# 3. Verify um-biz-0 is healthy
kubectl exec um-biz-0 -n <namespace> -- curl -s http://localhost:9000/health/

# 4. Continue rollout
kubectl patch statefulset um-biz -n <namespace> \
  -p '{"spec":{"updateStrategy":{"rollingUpdate":{"partition":1}}}}'

# 5. Final pod
kubectl patch statefulset um-biz -n <namespace> \
  -p '{"spec":{"updateStrategy":{"rollingUpdate":{"partition":0}}}}'
```

### Rollback

```bash
# View history
helm history um-biz -n <namespace>

# Rollback to previous version
helm rollback um-biz -n <namespace>

# Rollback to specific revision
helm rollback um-biz 2 -n <namespace>
```

---

## Backup and Recovery

### Backup Strategy

**1. PVC Snapshots:**
```bash
# Create volume snapshot
kubectl apply -f - <<EOF
apiVersion: snapshot.storage.k8s.io/v1
kind: VolumeSnapshot
metadata:
  name: um-biz-snapshot-$(date +%Y%m%d)
  namespace: <namespace>
spec:
  source:
    persistentVolumeClaimName: um-data-um-biz-0
EOF
```

**2. Helm Values Backup:**
```bash
helm get values um-biz -n <namespace> > backup/um-biz-values-$(date +%Y%m%d).yaml
```

### Recovery Procedures

**Restore from Helm values:**
```bash
helm upgrade --install um-biz . -n <namespace> -f backup/um-biz-values-20250109.yaml
```

**Restore from PVC snapshot:**
```bash
kubectl apply -f - <<EOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: um-data-um-biz-0-restored
  namespace: <namespace>
spec:
  storageClassName: default
  dataSource:
    name: um-biz-snapshot-20250109
    kind: VolumeSnapshot
    apiGroup: snapshot.storage.k8s.io
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
EOF
```

---

## Uninstall

### Active-Passive Cluster

```bash
# Uninstall helm release
helm uninstall um-biz -n <namespace>

# Delete PVCs (WARNING: data loss!)
kubectl delete pvc -l app=um-biz -n <namespace>
```

### Active-Active Clusters

```bash
# Uninstall all helm releases
helm uninstall um-logs-a um-logs-b um-logs-c -n <namespace>

# Delete all log cluster PVCs
kubectl delete pvc -l data-type=logs -n <namespace>
```

---

## Appendix

### Useful Commands Reference

```bash
# View all UM resources
kubectl get all -n <namespace> -l component=messaging

# Watch pod status
kubectl get pods -n <namespace> -l component=messaging -w

# Port forward for local access
kubectl port-forward svc/um-biz 9000:9000 -n <namespace>

# View resource usage
kubectl top pods -n <namespace> -l component=messaging

# Exec into pod
kubectl exec -it um-biz-0 -n <namespace> -- /bin/bash

# UM Tools in pod
kubectl exec um-biz-0 -n <namespace> -- runUMTool.sh --help
```

### Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `REALM_NAME` | (from values) | UM realm name |
| `INIT_JAVA_MEM_SIZE` | 512 | Initial JVM heap (MB) |
| `MAX_JAVA_MEM_SIZE` | 1536 | Maximum JVM heap (MB) |
| `MAX_DIRECT_MEM_SIZE` | 1G | Direct memory size |
| `BASIC_AUTH_ENABLE` | Y | Enable basic authentication |
| `LOG_FRAMEWORK` | log4j2 | Logging framework |

### Asset Configuration Reference

| Asset | Configuration |
|-------|---------------|
| **Queues** | `um.queues.enabled`, `um.queues.names`, `um.queues.type`, `um.queues.ttl`, `um.queues.capacity` |
| **Channels** | `um.channels.enabled`, `um.channels.names`, `um.channels.type`, `um.channels.ttl`, `um.channels.capacity` |
| **Durables** | `um.durables.enabled`, `um.durables.configs` (format: `channel:durable`) |
| **Connection Factories** | `um.connectionFactories.enabled`, `um.connectionFactories.configs` (format: `name:type`) |
| **JNDI Queues** | `um.jndiQueues.enabled`, `um.jndiQueues.names` (comma-separated native queue names to register in JNDI) |
| **JNDI Topics** | `um.jndiTopics.enabled`, `um.jndiTopics.names` (comma-separated native channel names to register in JNDI) |

### Storage Types

| Code | Name | Persistence | Use Case |
|------|------|-------------|----------|
| P | Persistent | Disk-backed | Critical business data |
| R | Reliable | Memory + Journal | Important with speed |
| M | Mixed | Per-event | High-throughput logs |
| S | Simple | Memory only | Ephemeral data |

---

*Document Version: 3.1*
*Last Updated: March 2026*
