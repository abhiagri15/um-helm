# Universal Messaging Helm Chart

[![Helm Version](https://img.shields.io/badge/Helm-3.12+-blue.svg)](https://helm.sh)
[![Kubernetes Version](https://img.shields.io/badge/Kubernetes-1.28+-blue.svg)](https://kubernetes.io)
[![UM Version](https://img.shields.io/badge/UM-11.1.2-green.svg)](https://www.softwareag.com)

Enterprise-grade Helm chart for deploying **Software AG Universal Messaging (UM)** on Azure Kubernetes Service (AKS) with support for both **Active-Passive** and **Active-Active** clustering modes.

---

## Documentation

| Document | Description |
|----------|-------------|
| **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** | System architecture, cluster design, HA patterns |
| **[docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md)** | Deployment guides, troubleshooting, upgrade procedures |
| **README.md** (this file) | Quick start guide and feature overview |

---

## Deployment Scenarios

This Helm chart supports **two primary deployment scenarios**:

### 1. Active-Passive (Single Cluster with Replicas)
**Use Case:** Business data requiring strong consistency and auto-failover

```
┌─────────────────────────────────────────────────────────┐
│            Active-Passive Cluster (3 Replicas)          │
│                                                         │
│   ┌────────────┐   ┌────────────┐   ┌────────────┐     │
│   │  um-biz-0  │◄─►│  um-biz-1  │◄─►│  um-biz-2  │     │
│   │  (MASTER)  │   │  (SLAVE)   │   │  (SLAVE)   │     │
│   └─────┬──────┘   └─────┬──────┘   └─────┬──────┘     │
│         │                │                │             │
│         └───────────┬────┴────────────────┘             │
│                     │                                   │
│            ┌────────▼────────┐                          │
│            │     um-biz      │◄────── MSR Pods          │
│            │    (Service)    │                          │
│            └─────────────────┘                          │
└─────────────────────────────────────────────────────────┘
```

**Benefits:**
- Auto-failover: Slaves automatically promote to Master
- Data replication: Messages synchronized across all nodes
- Single connection URL for clients

**Values File:** `values-business.yaml`

---

### 2. Active-Active (Multi-Cluster with Combined Service)
**Use Case:** High-throughput log ingestion requiring parallel writes

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Active-Active Multi-Cluster                          │
│                                                                         │
│   ┌─────────────┐      ┌─────────────┐      ┌─────────────┐           │
│   │  um-logs-a  │      │  um-logs-b  │      │  um-logs-c  │           │
│   │  (MASTER)   │      │  (MASTER)   │      │  (MASTER)   │           │
│   │  :9000      │      │  :9000      │      │  :9000      │           │
│   └──────┬──────┘      └──────┬──────┘      └──────┬──────┘           │
│          │                    │                    │                   │
│          └────────────────────┼────────────────────┘                   │
│                               │                                        │
│                    ┌──────────▼──────────┐                            │
│                    │  um-logs-combined   │◄────── MSR Publisher       │
│                    │  (Load Balancer)    │                            │
│                    └─────────────────────┘                            │
│                                                                         │
│   Note: Each cluster is independent. Consumers must read from          │
│         all three clusters to get all messages.                        │
└─────────────────────────────────────────────────────────────────────────┘
```

**Benefits:**
- 3x write throughput: All nodes accept writes simultaneously
- No single point of failure
- Horizontal scalability

**Values Files:** `values-logs-a.yaml`, `values-logs-b.yaml`, `values-logs-c.yaml`

---

## Features

### Core Capabilities
- **StatefulSet Deployment** - Stable network identities and persistent storage
- **Dynamic Naming** - Deploy multiple independent clusters in same namespace
- **Persistent Volumes** - Per-pod storage for message durability
- **Health Probes** - Kubernetes-native startup, liveness, and readiness checks

### Asset Management
Automatically create messaging assets on cluster initialization:

| Asset Type | Configuration Options |
|------------|----------------------|
| **Queues** | names, type (P/R/M/S), TTL, capacity |
| **Channels (Topics)** | names, type (P/R/M/S), TTL, capacity |
| **Durable Subscribers** | channel:durableName mapping |
| **Connection Factories** | name:type (default/queue/topic/xa) |
| **JNDI Queues** | Register native queues in JNDI namespace for JMS clients |
| **JNDI Topics** | Register native channels as topics in JNDI namespace |
| **Channel Joins** | Inter-realm channel linking |

> **JNDI Registration Method:** Both Active-Passive (cluster-init) and Active-Active (inter-realm-init) use `JndiRegistrar.java` to register entries as `javax.jms.*` types, matching on-prem Enterprise Manager behavior.

**Storage Types:**
- `P` = Persistent (survives restart, disk-backed)
- `R` = Reliable (survives restart, memory-backed)
- `M` = Mixed (configurable per event)
- `S` = Simple/Volatile (in-memory only)

### Operations
- **Prometheus Metrics** - JMX exporter on port 9200
- **Pod Disruption Budget** - Controlled maintenance windows
- **Rolling Updates** - Zero-downtime upgrades
- **Combined Service** - Single URL for Active-Active clusters

---

## Quick Start

### Prerequisites
- Azure subscription with AKS cluster
- Helm 3.12+ and kubectl configured
- UM container image in accessible registry

### 1. Create Namespace

```bash
kubectl create namespace <namespace>
```

> **Namespace flexibility:** The `-n` flag is the only namespace control. The same values files work in any namespace — no `global.namespace` needed. Kubernetes short DNS resolves service names within the same namespace automatically.

### 2. Deploy Business Data Cluster (Active-Passive)

```bash
# Single cluster with 3 replicas for business data
helm upgrade --install um-biz . -n <namespace> -f values-business.yaml
```

**Connection URL:** `nsp://um-biz:9000`

### 3. Deploy Log Data Clusters (Active-Active)

```bash
# Deploy all 3 log clusters (must deploy without hooks first)
helm upgrade --install um-logs-a . -n <namespace> -f values-logs-a.yaml --no-hooks
helm upgrade --install um-logs-b . -n <namespace> -f values-logs-b.yaml --no-hooks
helm upgrade --install um-logs-c . -n <namespace> -f values-logs-c.yaml --no-hooks

# Wait for pods to be ready
kubectl wait --for=condition=ready pod -l component=messaging,data-type=logs -n <namespace> --timeout=300s

# Upgrade to run init hooks
helm upgrade um-logs-a . -n <namespace> -f values-logs-a.yaml
helm upgrade um-logs-b . -n <namespace> -f values-logs-b.yaml
helm upgrade um-logs-c . -n <namespace> -f values-logs-c.yaml
```

**Connection URL (Combined):** `nsp://um-logs-combined:9000`

### 4. Verify Deployment

```bash
# Check pod status
kubectl get pods -n <namespace> -l component=messaging

# Check services
kubectl get svc -n <namespace> | grep um

# Test health endpoint
kubectl exec um-biz-0 -n <namespace> -- curl -s http://localhost:9000/health/
```

---

## Configuration Reference

### Values Files

| File | Description | Use Case |
|------|-------------|----------|
| `values-business.yaml` | Active-Passive, 3 replicas | Business data with consistency |
| `values-logs-a.yaml` | Active-Active cluster A | High throughput logs |
| `values-logs-b.yaml` | Active-Active cluster B | High throughput logs |
| `values-logs-c.yaml` | Active-Active cluster C | High throughput logs |

### Key Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `replicaCount` | Number of UM nodes | `3` (business) / `1` (logs) |
| `fullnameOverride` | Override release name | - |
| `persistence.size` | Storage per pod | `10Gi` / `20Gi` |
| `um.clustering.enabled` | Enable internal clustering | `true` / `false` |
| `um.clustering.mode` | Cluster mode | `active-passive` / `standalone` |
| `um.interRealm.enabled` | Enable multi-cluster | `false` / `true` |

### Asset Configuration Example

```yaml
um:
  # Queues
  queues:
    enabled: true
    names: "OrderQueue,InvoiceQueue"
    type: "P"           # P=Persistent, R=Reliable, M=Mixed, S=Simple
    ttl: 0              # Time-to-live in ms (0=unlimited)
    capacity: 0         # Max events (0=unlimited)

  # Channels (Topics)
  channels:
    enabled: true
    names: "EventChannel,NotificationChannel"
    type: "P"
    ttl: 0
    capacity: 0

  # Durable Subscribers (format: channel:durableName)
  durables:
    enabled: true
    configs: "EventChannel:EventProcessor,NotificationChannel:NotifyHandler"

  # JNDI Connection Factories (format: factoryName:factoryType)
  # Types: default (ConnectionFactory), queue (QueueConnectionFactory),
  #        topic (TopicConnectionFactory), xa (XAConnectionFactory)
  connectionFactories:
    enabled: true
    configs: "SmPubConnectionFactory:default,MyQueueFactory:queue"

  # JNDI Queue Registration (registers native queues in JNDI namespace)
  jndiQueues:
    enabled: true
    names: "OrderQueue,InvoiceQueue"

  # JNDI Topic Registration (registers native channels as topics in JNDI namespace)
  jndiTopics:
    enabled: true
    names: "EventChannel,NotificationChannel"
```

---

## Enabling JNDI Registration (Required for JMS Clients)

By default, the Helm chart creates **native UM queues and channels**, but these are not visible to JMS clients unless they are also registered in the **JNDI namespace**. If your MSR or other JMS applications use JNDI lookup to find queues/topics, you must enable JNDI registration.

### Step 1: Ensure Native Assets Are Configured

Your values file must have the native queues and channels defined:

```yaml
um:
  queues:
    enabled: true
    names: "KPILog_Q,EventLog_Q,smudEmail_Q,ErrorRouting_Q"
  channels:
    enabled: true
    names: "smud/logging/Entry_T,smud/logging/Event_T,smud/logging/Session_T,smud/logging/Exception_T"
```

### Step 2: Add JNDI Registration Sections

Add these sections under `um:` in your values file. The names **must match** the native queues/channels from Step 1:

```yaml
um:
  # ... existing queues, channels, connectionFactories ...

  jndiQueues:
    enabled: true
    names: "KPILog_Q,EventLog_Q,smudEmail_Q,ErrorRouting_Q"

  jndiTopics:
    enabled: true
    names: "smud/logging/Entry_T,smud/logging/Event_T,smud/logging/Session_T,smud/logging/Exception_T"
```

### Step 3: Deploy or Upgrade

```bash
helm upgrade --install um-biz . -n <namespace> -f values-business.yaml
```

### Step 4: Verify in UM Enterprise Manager

After deployment, the JNDI namespace in UM Enterprise Manager should show your queues and topics. You can also verify via CLI:

```bash
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ListChannels -rname="nsp://localhost:9000"
```

> **How it works:** The init job creates native assets first (`CreateQueue`/`CreateChannel`), then registers them in JNDI via `JndiRegistrar.java` — a custom Java program that uses Nirvana's JNDI context to register entries as `javax.jms.Queue`, `javax.jms.Topic`, and `javax.jms.ConnectionFactory` types. This matches on-prem Enterprise Manager behavior and makes entries visible in both EM and IS JNDI settings.
>
> **Both deployment modes supported:** JndiRegistrar is used by both the cluster-init job (Active-Passive) and the inter-realm-init job (Active-Active), ensuring JNDI parity across all deployment modes.

---

## MSR Integration

> **Note:** All URLs below use short DNS names (e.g., `um-biz:9000`). Kubernetes resolves these within the same namespace. No FQDN needed when MSR and UM are in the same namespace.

### Business Data (Active-Passive)

```yaml
# In MSR values.yaml
um:
  enabled: true
  url: "nsp://um-biz:9000"
  useCSQ: "true"
```

### Log Data (Active-Active)

**Publisher MSR:**
```yaml
um:
  enabled: true
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

---

## Common Operations

### View Cluster Status

```bash
# Active-Passive cluster state
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ClusterState -rname="nsp://localhost:9000"

# List all channels/queues
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ListChannels -rname="nsp://localhost:9000"

# List JNDI connection factories
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ListConnectionFactories -rname="nsp://localhost:9000"
```

### Scale Cluster (Active-Passive only)

```bash
kubectl scale statefulset um-biz --replicas=5 -n <namespace>
```

### View Logs

```bash
kubectl logs -f um-biz-0 -n <namespace>
```

### Upgrade

```bash
helm upgrade um-biz . -n <namespace> -f values-business.yaml --set image.tag="11.1.3"
```

### Uninstall

```bash
# Business cluster
helm uninstall um-biz -n <namespace>
kubectl delete pvc -l app=um-biz -n <namespace>

# Logs clusters
helm uninstall um-logs-a um-logs-b um-logs-c -n <namespace>
kubectl delete pvc -l data-type=logs -n <namespace>
```

---

## Troubleshooting

| Issue | Command |
|-------|---------|
| Pod stuck in Pending | `kubectl describe pod <pod-name> -n <namespace>` |
| Check events | `kubectl get events -n <namespace> --sort-by='.lastTimestamp'` |
| View UM logs | `kubectl logs <pod-name> -n <namespace>` |
| Test health | `kubectl exec <pod-name> -n <namespace> -- curl http://localhost:9000/health/` |
| Check PVC | `kubectl get pvc -n <namespace>` |
| Init job logs | `kubectl logs -l component=cluster-init -n <namespace>` |
| Inter-realm init logs | `kubectl logs -l component=inter-realm-init -n <namespace>` |

### Common Issues

**Init job timeout:** If the cluster init job times out, deploy with `--no-hooks` first, wait for pods to be ready, then upgrade without the flag.

**Queue/Channel not created:** Check init job logs for errors. Verify the asset names don't contain spaces.

**Connection factory not created:** Check init job logs. The connection factory URL is automatically set to the Kubernetes service URL (e.g., `nsp://um-biz.<namespace>.svc.cluster.local:9000`).

**JNDI queues/topics not visible:** Native queues/channels exist but JMS clients can't find them via JNDI lookup. Common causes:

1. **Missing values config**: Ensure `jndiQueues.enabled: true` and `jndiTopics.enabled: true` are set under `um:` in your values file, properly indented at the same level as `queues:` and `channels:`.
2. **Pre-existing native assets**: If native queues/channels were created before JNDI was enabled, `CreateJMSQueue`/`CreateJMSTopic` fail silently with "Channel already exists." **Fix**: Delete the existing native assets, then re-run the init job:
   ```bash
   # Delete existing native queues (adjust names for your deployment)
   kubectl exec <pod-name> -n <namespace> -- runUMTool.sh DeleteQueue -rname="nsp://localhost:9000" -queuename="<queue-name>"
   # Delete existing native channels
   kubectl exec <pod-name> -n <namespace> -- runUMTool.sh DeleteChannel -rname="nsp://localhost:9000" -channelname="<channel-name>"
   # Re-deploy to trigger init job
   helm upgrade <release> . -n <namespace> -f <values-file>
   ```
3. **Verify init job logs**: Look for `"=== Creating JNDI Queues"` and `"=== Creating JNDI Topics"` in the init job output. If these lines are absent, the JNDI config is not being passed to the job.

---

## Monitoring

### Prometheus Metrics

UM exposes JMX metrics on port 9200:

```yaml
podAnnotations:
  prometheus.io/scrape: "true"
  prometheus.io/port: "9200"
```

### Key Metrics

| Metric | Description |
|--------|-------------|
| `um_connections_active` | Active client connections |
| `um_messages_pending` | Pending messages in queues |
| `um_memory_used` | JVM heap usage |
| `um_cluster_nodes` | Cluster membership count |

---

## Version History

### Current Version: 3.1.0 (March 2026)

- **Namespace Flexibility** - Replaced `global.namespace` with `.Release.Namespace` in all templates. Namespace is now controlled solely by the `-n` flag — same values files work in any namespace
- **Short DNS Names** - All cross-component URLs use short DNS (e.g., `um-biz:9000`) instead of FQDNs. Kubernetes resolves within the same namespace automatically
- **Inter-Realm JNDI Parity** - Inter-realm-init job now uses `JndiRegistrar.java` (same as cluster-init) for JNDI registration, ensuring `javax.jms.*` types and UM Enterprise Manager visibility for Active-Active deployments
- **FIRST_POD_URL Bug Fix** - Fixed undefined `FIRST_POD_URL` variable in inter-realm-init that caused JNDI registration to silently fail for UM Logs clusters

### 3.0.0 (March 2026)

- **Java-based JNDI Registration** - Replaced CLI tools with `JndiRegistrar.java` for `javax.jms.*` type registration visible in UM Enterprise Manager
- **CreateCluster Fallback** - `CreateCluster` tries `-convertlocal=true` first, falls back to `-convertlocal=false`

### 2.2.0 (March 2026)

- **JNDI Registration Bug Fix** - Fixed critical ordering issue where `CreateJMSQueue`/`CreateJMSTopic` failed because native assets were created first
- **CreateJMSTopic Parameter Fix** - Fixed incorrect `-topicname` parameter to correct `-channelname`
- **Channel Existence Check Fix** - Fixed `ListChannels` grep pattern to handle `/` prefix on channel names

### 2.1.0 (March 2026)

- **JNDI Queue/Topic Registration** - Automatically register native queues and channels in JNDI namespace for JMS client lookup

### 2.0.0 (January 2025)

- **Dual Architecture** - Support for both Active-Passive and Active-Active modes
- **Asset Management** - Automatic creation of queues, channels, durables, and JNDI connection factories
- **Combined Service** - Load-balanced service for Active-Active clusters
- **Dynamic Naming** - Deploy multiple clusters in same namespace

### Previous Versions

- **1.1.0** - Documentation and health probes
- **1.0.0** - Initial StatefulSet deployment

---

## Support

For issues and questions:

1. Review [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md) for deployment guides
2. Check UM logs: `kubectl logs <pod-name> -n <namespace>`
3. Verify pod health: `kubectl exec <pod-name> -n <namespace> -- curl http://localhost:9000/health/`
4. Check service endpoints: `kubectl get endpoints -n <namespace>`

---

## License

This Helm chart is provided for use with licensed webMethods products from Software AG.

---

*Maintained by: webMethods Architecture Team*
*Chart Version: 3.1.0 | Last Updated: March 2026*
