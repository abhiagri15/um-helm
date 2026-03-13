# Universal Messaging Helm Chart - Architecture Guide

## Document Information

| Attribute | Value |
|-----------|-------|
| Version | 3.1.0 |
| Last Updated | March 2026 |
| Author | webMethods Platform Team |
| Classification | Internal |

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Deployment Modes](#3-deployment-modes)
4. [Component Architecture](#4-component-architecture)
5. [High Availability Architecture](#5-high-availability-architecture)
6. [Networking Architecture](#6-networking-architecture)
7. [Storage Architecture](#7-storage-architecture)
8. [Asset Management](#8-asset-management)
9. [Integration Patterns](#9-integration-patterns)
10. [Monitoring & Observability](#10-monitoring--observability)

---

## 1. Executive Summary

This document describes the reference architecture for deploying Software AG Universal Messaging (UM) on Azure Kubernetes Service (AKS) using Helm charts. The chart supports **two distinct deployment modes** to address different use cases:

| Mode | Use Case | Topology |
|------|----------|----------|
| **Active-Passive** | Business data with consistency | Single cluster, 3 replicas |
| **Active-Active** | High-throughput logs | Multiple independent clusters |

### Key Design Principles

| Principle | Implementation |
|-----------|----------------|
| Flexibility | Support both Active-Passive and Active-Active modes |
| High Availability | Automatic failover (A-P) or multi-cluster redundancy (A-A) |
| Data Persistence | StatefulSet with dedicated PVCs per node |
| Zero Message Loss | Durable subscriptions and persistent channels |
| Scalability | Vertical (A-P) or horizontal (A-A) scaling |
| Asset Automation | Auto-create queues, channels, durables, connection factories, and JNDI registrations on deployment |
| Namespace Flexibility | Namespace controlled solely by `-n` flag — same values files deploy to any namespace |

### Universal Messaging Capabilities

- **JMS Messaging**: Full JMS 1.1 and 2.0 support
- **Pub/Sub Patterns**: Topics with durable/non-durable subscriptions
- **Queuing**: Point-to-point message queues
- **Clustering**: Master-Slave or multi-cluster topologies
- **Protocol Support**: NSP, NHP, NHPS (SSL)

---

## 2. Architecture Overview

### 2.1 Dual Architecture Support

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              DEPLOYMENT MODES                                    │
├─────────────────────────────────┬───────────────────────────────────────────────┤
│                                 │                                               │
│      ACTIVE-PASSIVE             │           ACTIVE-ACTIVE                       │
│   (Single Cluster Mode)         │        (Multi-Cluster Mode)                   │
│                                 │                                               │
│  ┌─────────────────────────┐    │    ┌───────────┐ ┌───────────┐ ┌───────────┐ │
│  │    um-biz          │    │    │um-logs-a  │ │um-logs-b  │ │um-logs-c  │ │
│  │ ┌─────┐┌─────┐┌─────┐   │    │    │ (MASTER)  │ │ (MASTER)  │ │ (MASTER)  │ │
│  │ │  0  ││  1  ││  2  │   │    │    └─────┬─────┘ └─────┬─────┘ └─────┬─────┘ │
│  │ │MASTR││SLAVE││SLAVE│   │    │          │             │             │       │
│  │ └──┬──┘└──┬──┘└──┬──┘   │    │          └─────────────┼─────────────┘       │
│  │    └──────┼──────┘      │    │                        │                     │
│  │           │             │    │             ┌──────────▼──────────┐          │
│  │    ┌──────▼──────┐      │    │             │  um-logs-combined   │          │
│  │    │ um-biz │      │    │             │  (Load Balancer)    │          │
│  │    │  (Service)  │      │    │             └─────────────────────┘          │
│  │    └─────────────┘      │    │                                               │
│  │                         │    │                                               │
│  │  - 1 Master + N Slaves  │    │    - Each cluster is independent Master      │
│  │  - Auto-failover        │    │    - 3x write throughput                     │
│  │  - Data replication     │    │    - Publishers use combined service         │
│  │  - Single connection    │    │    - Consumers connect to each cluster       │
│  └─────────────────────────┘    └───────────────────────────────────────────────┘
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 When to Use Each Mode

| Requirement | Active-Passive | Active-Active |
|-------------|----------------|---------------|
| Data consistency | Yes | No (eventual) |
| High write throughput | Limited | Yes (3x) |
| Simple consumer setup | Yes | No (multi-connection) |
| Auto-failover | Yes | N/A (no slaves) |
| Message replication | Yes | No (independent) |

---

## 3. Deployment Modes

### 3.1 Active-Passive Mode (Business Data)

**Use Case:** Transaction data, orders, invoices - where consistency matters.

```
┌─────────────────────────────────────────────────────────────────┐
│              Active-Passive Cluster (3 Replicas)                │
│                                                                 │
│   ┌─────────────────┐                                          │
│   │   um-biz-0 │  MASTER                                  │
│   │    (Primary)    │  - Accepts all writes                    │
│   │     :9000       │  - Replicates to slaves                  │
│   └────────┬────────┘                                          │
│            │                                                    │
│            │  Cluster Sync (Bi-directional)                    │
│            │                                                    │
│   ┌────────┴────────┬─────────────────┐                       │
│   │                 │                 │                        │
│   ▼                 ▼                 │                        │
│ ┌─────────────┐ ┌─────────────┐       │                        │
│ │um-biz-1│ │um-biz-2│       │                        │
│ │   (SLAVE)   │ │   (SLAVE)   │       │                        │
│ │   :9000     │ │   :9000     │       │                        │
│ └──────┬──────┘ └──────┬──────┘       │                        │
│        │               │              │                        │
│        └───────────────┴──────────────┘                        │
│                        │                                        │
│               ┌────────▼────────┐                              │
│               │   um-biz   │◄──── MSR Pods                │
│               │   (ClusterIP)   │                              │
│               └─────────────────┘                              │
│                                                                 │
│   Connection URL: nsp://um-biz:9000                       │
└─────────────────────────────────────────────────────────────────┘
```

**Characteristics:**
- **Write Path:** All writes go to Master
- **Read Path:** Any node can serve reads
- **Failover:** Automatic Master election if Master fails
- **Replication:** Synchronous within cluster

**Values File:** `values-business.yaml`

### 3.2 Active-Active Mode (Log Data)

**Use Case:** High-volume log ingestion where throughput > consistency.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Active-Active Multi-Cluster                              │
│                                                                             │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐           │
│   │   um-logs-a     │  │   um-logs-b     │  │   um-logs-c     │           │
│   │                 │  │                 │  │                 │           │
│   │   ┌─────────┐   │  │   ┌─────────┐   │  │   ┌─────────┐   │           │
│   │   │ MASTER  │   │  │   │ MASTER  │   │  │   │ MASTER  │   │           │
│   │   │   -0    │   │  │   │   -0    │   │  │   │   -0    │   │           │
│   │   │  :9000  │   │  │   │  :9000  │   │  │   │  :9000  │   │           │
│   │   └────┬────┘   │  │   └────┬────┘   │  │   └────┬────┘   │           │
│   │        │        │  │        │        │  │        │        │           │
│   │   ┌────▼────┐   │  │   ┌────▼────┐   │  │   ┌────▼────┐   │           │
│   │   │  PVC    │   │  │   │  PVC    │   │  │   │  PVC    │   │           │
│   │   │  20Gi   │   │  │   │  20Gi   │   │  │   │  20Gi   │   │           │
│   │   └─────────┘   │  │   └─────────┘   │  │   └─────────┘   │           │
│   └────────┬────────┘  └────────┬────────┘  └────────┬────────┘           │
│            │                    │                    │                     │
│            └────────────────────┼────────────────────┘                     │
│                                 │                                          │
│                      ┌──────────▼──────────┐                              │
│                      │  um-logs-combined   │◄──── MSR Publisher           │
│                      │  (Load Balancer)    │      (round-robin)           │
│                      │     :9000           │                              │
│                      └─────────────────────┘                              │
│                                                                             │
│   Publisher URL: nsp://um-logs-combined:9000                               │
│                                                                             │
│   Consumer URLs (must connect to ALL):                                     │
│   - nsp://um-logs-a:9000                                                    │
│   - nsp://um-logs-b:9000                                                    │
│   - nsp://um-logs-c:9000                                                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Characteristics:**
- **Write Path:** Round-robin across clusters via combined service
- **Read Path:** Consumers must connect to each cluster separately
- **Failover:** N/A (each cluster is independent)
- **Replication:** None between clusters

**Values Files:** `values-logs-a.yaml`, `values-logs-b.yaml`, `values-logs-c.yaml`

---

## 4. Component Architecture

### 4.1 Kubernetes Resources

| Resource | Active-Passive | Active-Active |
|----------|----------------|---------------|
| StatefulSet | 1 (3 replicas) | 3 (1 replica each) |
| Service (ClusterIP) | 1 | 3 + 1 combined |
| Service (Headless) | 1 | 3 |
| PDB | 1 (minAvailable: 2) | 0 (single node) |
| ConfigMap (init) | 1 | 3 |
| Job (init) | 1 | 3 |
| PVC | 3 | 3 |

### 4.2 Helm Chart Structure

```
um-helm/
├── README.md                     # Quick start guide
├── CLIENT_JNDI_FIX.md           # JNDI fix history and migration guide
├── Chart.yaml                    # Chart metadata
├── values-business.yaml          # Active-Passive config
├── values-logs-a.yaml            # Active-Active cluster A
├── values-logs-b.yaml            # Active-Active cluster B
├── values-logs-c.yaml            # Active-Active cluster C
├── files/
│   └── JndiRegistrar.java       # Java JNDI registrar (javax.jms.* types)
├── docs/
│   ├── ARCHITECTURE.md           # This document
│   └── IMPLEMENTATION.md         # Deployment guides
└── templates/
    ├── _helpers.tpl              # Helm template helpers
    ├── statefulset.yaml          # UM StatefulSet
    ├── service.yaml              # Services (ClusterIP + Headless)
    ├── pdb.yaml                  # Pod Disruption Budget
    ├── cluster-init-configmap.yaml    # A-P init script
    ├── cluster-init-job.yaml          # A-P init job
    ├── inter-realm-init-configmap.yaml # A-A init script
    ├── inter-realm-init-job.yaml      # A-A init job
    ├── jndi-registrar-configmap.yaml  # JndiRegistrar.java ConfigMap
    └── combined-service.yaml          # A-A load balancer
```

### 4.3 Container Ports

| Port | Name | Protocol | Purpose |
|------|------|----------|---------|
| 9000 | nsp | TCP | NSP client connections, health endpoint |
| 9200 | jmx | TCP | JMX Prometheus metrics exporter |

### 4.4 Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| REALM_NAME | (from values) | UM realm name |
| INIT_JAVA_MEM_SIZE | 512 | Initial heap (MB) |
| MAX_JAVA_MEM_SIZE | 1536 | Maximum heap (MB) |
| MAX_DIRECT_MEM_SIZE | 1G | Direct memory |
| BASIC_AUTH_ENABLE | Y | Enable basic auth |
| LOG_FRAMEWORK | log4j2 | Logging framework |

---

## 5. High Availability Architecture

### 5.1 Active-Passive HA

```
┌─────────────────────────────────────────────────────────────────┐
│                    Failover Scenario                             │
│                                                                  │
│   NORMAL OPERATION:              AFTER MASTER FAILURE:          │
│                                                                  │
│   ┌─────────┐                    ┌─────────┐                    │
│   │ Master  │ ◄── Writes         │ (down)  │                    │
│   │   -0    │                    │   -0    │                    │
│   └────┬────┘                    └─────────┘                    │
│        │                                                         │
│   ┌────┴────┐                    ┌─────────┐                    │
│   │         │                    │ Master  │ ◄── Writes         │
│   ▼         ▼                    │   -1    │   (promoted)       │
│ ┌─────┐ ┌─────┐                  └────┬────┘                    │
│ │Slave│ │Slave│                       │                         │
│ │ -1  │ │ -2  │                  ┌────┴────┐                    │
│ └─────┘ └─────┘                  │         │                    │
│                                  ▼         ▼                    │
│                                ┌─────┐ ┌─────┐                  │
│                                │Slave│ │Slave│                  │
│                                │ -0  │ │ -2  │                  │
│                                │(rec)│ └─────┘                  │
│                                └─────┘                          │
│                                                                  │
│   Recovery: Pod -0 restarts and rejoins as Slave                │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 Active-Active HA

```
┌─────────────────────────────────────────────────────────────────┐
│                    Multi-Cluster Resilience                      │
│                                                                  │
│   NORMAL OPERATION:              AFTER CLUSTER FAILURE:         │
│                                                                  │
│   ┌───┐ ┌───┐ ┌───┐              ┌───┐         ┌───┐           │
│   │ A │ │ B │ │ C │              │ A │ (down)  │ C │           │
│   └─┬─┘ └─┬─┘ └─┬─┘              └───┘         └─┬─┘           │
│     └─────┼─────┘                         ┌─────┘              │
│           │                               │                     │
│    ┌──────▼──────┐                 ┌──────▼──────┐             │
│    │  Combined   │                 │  Combined   │             │
│    │  (3 pods)   │                 │  (2 pods)   │             │
│    └──────┬──────┘                 └──────┬──────┘             │
│           │                               │                     │
│        Writes                          Writes                   │
│     (round-robin)                  (round-robin)               │
│                                                                  │
│   Impact: 33% throughput reduction, no data loss for new msgs  │
│   Note: Messages already in cluster B are unavailable          │
└─────────────────────────────────────────────────────────────────┘
```

### 5.3 Pod Disruption Budget

**Active-Passive:**
```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: um-biz-pdb
spec:
  minAvailable: 2        # At least 2 pods must be available
  selector:
    matchLabels:
      app: um-biz
```

**Active-Active:** PDB disabled (single node per cluster)

### 5.4 Pod Anti-Affinity (Active-Passive)

```yaml
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          topologyKey: kubernetes.io/hostname
          labelSelector:
            matchLabels:
              app: um-biz
```

---

## 6. Networking Architecture

### 6.1 Service Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Service Architecture                                 │
│                                                                              │
│   ACTIVE-PASSIVE:                    ACTIVE-ACTIVE:                         │
│                                                                              │
│   ┌─────────────────────┐            ┌─────────────────────┐               │
│   │    um-biz      │            │   um-logs-combined  │               │
│   │    (ClusterIP)      │            │    (ClusterIP)      │               │
│   │                     │            │                     │               │
│   │  Selector:          │            │  Selector:          │               │
│   │   app: um-biz  │            │   component: messaging              │
│   │                     │            │   tier: middleware  │               │
│   │  Routes to any      │            │   data-type: logs   │               │
│   │  of 3 replicas      │            │                     │               │
│   └─────────────────────┘            │  Routes to all 3    │               │
│                                      │  cluster pods       │               │
│   ┌─────────────────────┐            └─────────────────────┘               │
│   │  um-biz-headless│                                                  │
│   │    (Headless)       │            ┌───────────┬───────────┬───────────┐ │
│   │                     │            │um-logs-a  │um-logs-b  │um-logs-c  │ │
│   │  For StatefulSet    │            │(ClusterIP)│(ClusterIP)│(ClusterIP)│ │
│   │  DNS resolution     │            └───────────┴───────────┴───────────┘ │
│   └─────────────────────┘                                                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 Connection URLs

> **Short DNS:** All URLs use short DNS names. Kubernetes resolves them within the same namespace via the pod DNS search path (`<namespace>.svc.cluster.local`). No hardcoded namespace in URLs = deploy to any namespace with zero value changes.

| Mode | Purpose | URL (short DNS) |
|------|---------|-----|
| Active-Passive | Client connection | `nsp://um-biz:9000` |
| Active-Passive | Direct to pod | `nsp://um-biz-0.um-biz-headless:9000` |
| Active-Active | Publisher | `nsp://um-logs-combined:9000` |
| Active-Active | Consumer (A) | `nsp://um-logs-a:9000` |
| Active-Active | Consumer (B) | `nsp://um-logs-b:9000` |
| Active-Active | Consumer (C) | `nsp://um-logs-c:9000` |

> **Cross-namespace access:** If a client in a different namespace needs to reach UM, use the FQDN: `nsp://um-biz.<namespace>.svc.cluster.local:9000`

### 6.3 Combined Service (Active-Active)

The combined service uses **label selectors** to route traffic to all log cluster pods:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: um-logs-combined
spec:
  type: ClusterIP
  selector:
    component: messaging
    tier: middleware
    data-type: logs      # Only pods with this label
  ports:
    - name: nsp
      port: 9000
```

All log cluster values files must include matching pod labels:

```yaml
podLabels:
  component: messaging
  tier: middleware
  data-type: logs
```

---

## 7. Storage Architecture

### 7.1 Persistent Volume Configuration

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Storage Architecture                                  │
│                                                                              │
│   ACTIVE-PASSIVE (um-biz):      ACTIVE-ACTIVE (um-logs-*):            │
│                                                                              │
│   ┌─────────────┐ ┌─────────────┐    ┌─────────────┐                       │
│   │um-biz-0│ │um-biz-1│    │ um-logs-a-0 │                       │
│   └──────┬──────┘ └──────┬──────┘    └──────┬──────┘                       │
│          │               │                   │                              │
│   ┌──────▼──────┐ ┌──────▼──────┐    ┌──────▼──────┐                       │
│   │  PVC 10Gi   │ │  PVC 10Gi   │    │  PVC 20Gi   │  (larger for logs)    │
│   └─────────────┘ └─────────────┘    └─────────────┘                       │
│                                                                              │
│   Mount Path: /opt/softwareag/UniversalMessaging/server/umserver/data      │
│                                                                              │
│   Contents:                                                                  │
│   - Channel/Queue data                                                      │
│   - Persistent messages                                                     │
│   - Durable subscriber state                                                │
│   - Configuration                                                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Storage Recommendations

| Deployment | Size | Storage Class | Use Case |
|------------|------|---------------|----------|
| Business (A-P) | 10Gi | default | Transaction data |
| Logs (A-A) | 20Gi | default | High-volume logs |
| Production | 50Gi+ | managed-premium | Enterprise workloads |

---

## 8. Asset Management

### 8.1 Automatic Asset Creation

Assets are automatically created during cluster initialization via Kubernetes Jobs:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Asset Creation Flow                           │
│                                                                  │
│   helm install                                                   │
│        │                                                         │
│        ▼                                                         │
│   ┌─────────────┐                                               │
│   │ StatefulSet │ ──► Pods start                                │
│   └─────────────┘                                               │
│        │                                                         │
│        ▼                                                         │
│   ┌─────────────┐     ┌──────────────────────────────────────┐ │
│   │  Init Job   │ ──► │ 1. Wait for UM pods to be ready      │ │
│   │  (Hook)     │     │ 2. Create cluster (Active-Passive)   │ │
│   └─────────────┘     │    or inter-realm links (Active-Active)│ │
│                       │ 3. Compile JndiRegistrar.java        │ │
│                       │ 4. Create native queues (for JNDI)   │ │
│                       │ 5. Create native channels (for JNDI) │ │
│                       │ 6. Create additional native queues    │ │
│                       │ 7. Create additional native channels  │ │
│                       │ 8. Create durable subscribers         │ │
│                       │ 9. Register JNDI via Java API:       │ │
│                       │    - Queues (javax.jms.Queue)        │ │
│                       │    - Topics (javax.jms.Topic)        │ │
│                       │    - ConnectionFactories             │ │
│                       └──────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### 8.2 Asset Configuration Options

**Queues:**
```yaml
um:
  queues:
    enabled: true
    names: "OrderQueue,InvoiceQueue"
    type: "P"           # P=Persistent, R=Reliable, M=Mixed, S=Simple
    ttl: 0              # Time-to-live in ms (0=unlimited)
    capacity: 0         # Max events (0=unlimited)
```

**Channels (Topics):**
```yaml
um:
  channels:
    enabled: true
    names: "EventChannel,NotificationChannel"
    type: "P"
    ttl: 0
    capacity: 0
```

**Durable Subscribers:**
```yaml
um:
  durables:
    enabled: true
    configs: "EventChannel:EventProcessor,NotificationChannel:NotifyHandler"
    # Format: channelName:durableName (comma-separated)
```

**JNDI Connection Factories:**
```yaml
um:
  connectionFactories:
    enabled: true
    configs: "SmPubConnectionFactory:default,smLoggingDurableConnectionFactory:default"
    # Format: factoryName:factoryType (comma-separated)
    # Types: default (ConnectionFactory), queue (QueueConnectionFactory),
    #        topic (TopicConnectionFactory), xa (XAConnectionFactory)
```

**JNDI Queue Registration:**
```yaml
um:
  jndiQueues:
    enabled: true
    names: "KPILog_Q,EventLog_Q,smudEmail_Q,ErrorRouting_Q"
    # Registers native UM queues in the JNDI namespace
    # so JMS clients can look them up via JNDI
```

**JNDI Topic Registration:**
```yaml
um:
  jndiTopics:
    enabled: true
    names: "smud/logging/Entry_T,smud/logging/Event_T"
    # Registers native UM channels as topics in the JNDI namespace
```

> **How it works:** The init script creates native assets first (`CreateQueue`/`CreateChannel`), then registers them in JNDI via `JndiRegistrar.java` — a custom Java program that registers entries as `javax.jms.Queue`, `javax.jms.Topic`, and `javax.jms.ConnectionFactory` types. This matches on-prem Enterprise Manager behavior. Both the cluster-init (Active-Passive) and inter-realm-init (Active-Active) jobs use the same JndiRegistrar approach for full parity.

### 8.3 Connection Factory Types

| Type | JNDI Class | Use Case |
|------|-----------|----------|
| `default` | `ConnectionFactory` | General-purpose JMS connections |
| `queue` | `QueueConnectionFactory` | Point-to-point queue messaging |
| `topic` | `TopicConnectionFactory` | Publish/subscribe topic messaging |
| `xa` | `XAConnectionFactory` | Distributed transactions (XA) |

### 8.4 Storage Type Comparison

| Type | Code | Persistence | Performance | Use Case |
|------|------|-------------|-------------|----------|
| Persistent | P | Disk-backed | Slower | Critical business data |
| Reliable | R | Memory + Journal | Faster | Important with speed |
| Mixed | M | Per-event config | Variable | High-throughput logs |
| Simple | S | Memory only | Fastest | Ephemeral data |

---

## 9. Integration Patterns

### 9.1 MSR to UM (Active-Passive)

```yaml
# MSR values.yaml — short DNS (resolves within same namespace)
um:
  enabled: true
  connectionAlias: "IS_UM_CONNECTION"
  url: "nsp://um-biz:9000"
  useCSQ: "true"        # Enable Client Store Queue
  csqSize: "-1"         # Unlimited CSQ size
```

### 9.2 MSR to UM (Active-Active)

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

### 9.3 Client Store Queue (CSQ) Pattern

```
┌─────────────────────────────────────────────────────────────────┐
│                 Client Store Queue Pattern                       │
│                                                                  │
│  ┌──────────┐                              ┌──────────┐         │
│  │   MSR    │                              │    UM    │         │
│  │          │    1. Send Message           │          │         │
│  │ ┌──────┐ │─────────────────────────────►│ ┌──────┐ │         │
│  │ │ App  │ │                              │ │Queue │ │         │
│  │ └──────┘ │                              │ └──────┘ │         │
│  │          │                              │          │         │
│  │ ┌──────┐ │    2. UM Unavailable         │          │         │
│  │ │ CSQ  │◄┼─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│          │         │
│  │ │Local │ │    Store locally             │          │         │
│  │ └──────┘ │                              │          │         │
│  │          │                              │          │         │
│  │ ┌──────┐ │    3. UM Available           │ ┌──────┐ │         │
│  │ │ CSQ  │─┼─────────────────────────────►│ │Queue │ │         │
│  │ │Drain │ │    Drain CSQ to UM           │ │      │ │         │
│  │ └──────┘ │                              │ └──────┘ │         │
│  └──────────┘                              └──────────┘         │
│                                                                  │
│  CSQ ensures zero message loss when UM is temporarily down      │
└─────────────────────────────────────────────────────────────────┘
```

---

## 10. Monitoring & Observability

### 10.1 Prometheus Metrics

```yaml
podAnnotations:
  prometheus.io/scrape: "true"
  prometheus.io/port: "9200"
  prometheus.io/path: "/metrics"
```

### 10.2 Key Metrics

| Metric | Description | Alert Threshold |
|--------|-------------|-----------------|
| um_connections_active | Active client connections | > 1000 |
| um_channels_count | Number of channels | N/A (info) |
| um_messages_pending | Pending messages in queues | > 10000 |
| um_memory_used | JVM heap usage | > 80% |
| um_cluster_nodes | Cluster membership count | < expected |

### 10.3 Health Endpoints

| Endpoint | Port | Purpose |
|----------|------|---------|
| `/health/` | 9000 | Kubernetes probes |
| `/metrics` | 9200 | Prometheus metrics |

### 10.4 Useful Commands

```bash
# Check cluster state (Active-Passive)
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ClusterState -rname="nsp://localhost:9000"

# List all channels/queues
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ListChannels -rname="nsp://localhost:9000"

# List JNDI connection factories
kubectl exec um-biz-0 -n <namespace> -- \
  runUMTool.sh ListConnectionFactories -rname="nsp://localhost:9000"

# Check init job logs
kubectl logs -l component=cluster-init -n <namespace>

# Check inter-realm init job logs
kubectl logs -l component=inter-realm-init -n <namespace>
```

---

## Appendix A: Comparison Summary

| Feature | Active-Passive | Active-Active |
|---------|----------------|---------------|
| Clusters | 1 | 3 |
| Replicas | 3 | 1 each |
| Write nodes | 1 (Master) | 3 (all Masters) |
| Failover | Automatic | N/A |
| Replication | Yes | No |
| Consumer setup | Single URL | Multiple URLs |
| PDB | Yes (minAvail: 2) | No |
| Combined service | No | Yes |
| Use case | Business data | Log data |

---

## Appendix B: Related Documents

| Document | Purpose |
|----------|---------|
| [README.md](../README.md) | Quick start guide |
| [IMPLEMENTATION.md](IMPLEMENTATION.md) | Step-by-step deployment |

---

*Document maintained by the webMethods Platform Team.*
*Last Updated: March 2026*
