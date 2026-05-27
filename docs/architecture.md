# Architecture: Probabilistic Valuation Engine

## Overview

A multi-module data pipeline that fetches MapleStory character data from Nexon Open API, calculates item expectation values, and serves results via REST API. Built on Spring Boot with Kafka-based choreography, PostgreSQL for read models, and JSONL.gz artifacts for large data interchange.

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Nexon Open API                                  │
│  (Ranking, OCID Lookup, Character Basic, Item Equipment)                │
└──────────────────────────┬──────────────────────────────────────────────┘
                           │ WebClient (rate-limited)
                           ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      module-external-api :8081                          │
│                                                                         │
│  ┌──────────┐   ┌───────────┐   ┌──────────────┐   ┌───────────────┐   │
│  │ Ranking  │──▶│ OCID      │──▶│ Character    │──▶│ Item          │   │
│  │ Fetch    │   │ Lookup    │   │ Basic Fetch  │   │ Equipment     │   │
│  └──────────┘   └───────────┘   └──────────────┘   └───────┬───────┘   │
│       │              │                │                      │          │
│       ▼              ▼                ▼                      ▼          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │              ChunkedSnapshotSink (JSONL.gz artifacts)            │   │
│  │    ../data/runs/{runId}/{endpoint}/chunks/part-{NNNN}.jsonl.gz   │   │
│  └──────────────────────────┬───────────────────────────────────────┘   │
│                             │ Kafka: snapshot_chunk-ready               │
│                             ▼                                           │
│  ┌──────────────────────────────────────────┐   ┌────────────────────┐  │
│  │  ConsumedChunkCleanupScheduler           │   │  Cron Scheduler    │  │
│  │  (Kafka event → virtual thread delete)   │   │  03:00 KST daily   │  │
│  └──────────────────────────────────────────┘   └────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
         │                                    │
         │ Kafka: snapshot_chunk-ready        │ Kafka: result_chunk-ready
         ▼                                    ▼
┌──────────────────────────┐    ┌─────────────────────────────────────────┐
│  module-calculator :8082  │    │          module-synchronizer :8083       │
│                           │    │                                         │
│  Kafka Consumer           │    │  Kafka Consumer                         │
│       │                   │    │       │                                 │
│       ▼                   │    │       ▼                                 │
│  ┌──────────────────┐     │    │  ┌──────────────────────┐               │
│  │ GzipSource → JSON │     │    │  │ GzipSource → JSON    │               │
│  │ Parse & Validate  │     │    │  │ Parse & Build Docs   │               │
│  └───────┬──────────┘     │    │  └──────────┬───────────┘               │
│          ▼                │    │             ▼                           │
│  ┌──────────────────┐     │    │  ┌──────────────────────┐               │
│  │ Expectation Calc  │     │    │  │ JdbcChunkedBatch     │               │
│  │ (per-item scoring)│     │    │  │ Executor (upsert)    │               │
│  └───────┬──────────┘     │    │  └──────────┬───────────┘               │
│          ▼                │    │             ▼                           │
│  ┌──────────────────┐     │    │  ┌──────────────────────┐               │
│  │ GzipSink → JSONL  │     │    │  │ PostgreSQL           │               │
│  │ Result Artifacts  │     │    │  │ character_basic_     │               │
│  └──────────────────┘     │    │  │ character_equipment_  │               │
│          │                │    │  │ read_model            │               │
│          │ Kafka:         │    │  └──────────────────────┘               │
│          │ result_chunk-  │    │             │                           │
│          │ ready          │    │             │ publishes                  │
│          ▼                │    │             │ CHUNK_CONSUMED             │
└──────────┼────────────────┘    └─────────────┼───────────────────────────┘
           │                                   │
           ▼                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        module-rest-controller :8080                      │
│                                                                         │
│  GET /api/v5/characters/{ign}/expectation                               │
│       │                                                                 │
│       ▼                                                                 │
│  TieredCache (L1: Caffeine → L2: PostgreSQL UNLOGGED)                  │
│       │                                                                 │
│       ▼                                                                 │
│  PostgreSQL read models (character_equipment_read_model)                │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Module Dependency Graph

```
module-common (zero Spring dependencies)
    ↑
module-core (pure domain, Jackson + Kotlin only)
    ↑
module-infra (JPA, WebClient, Redis, Kafka adapters)
    ↑
module-web (controllers, DTOs, security)
    ↑
module-app (Spring Boot wiring, legacy)

Independent service modules:
  module-external-api  →  module-core, module-infra
  module-calculator    →  module-core
  module-synchronizer  →  module-core, module-infra
  module-rest-controller → module-infra
```

---

## Core Patterns

### Claim Check Pattern

Large payloads (character profiles, item equipment) are never sent through Kafka directly. Instead:

1. **External API** writes data to JSONL.gz chunk files on shared storage
2. Kafka message carries only metadata: `{runId, endpoint, chunkId, objectKey}`
3. **Calculator/Synchronizer** read the actual data from the artifact file
4. After consumption, cleanup scheduler deletes both source and result files

This prevents Kafka broker overload — each chunk can be 50-100 MB uncompressed but the Kafka message is ~500 bytes.

### JSONL.gz Artifact Strategy

- **Format:** Newline-delimited JSON, gzip compressed
- **Naming:** `part-{NNNN}.jsonl.gz` (zero-padded 4-digit chunk index)
- **Location:** `../data/runs/{runId}/{endpoint}/chunks/`
- **Chunk size:** ~1,500 records per chunk (configurable)
- **Compression ratio:** 14-22x (10 KB record → 500-700 bytes compressed)

### Chunk Processing

- **External API** splits each endpoint's data into chunks of ~1,500 records
- Each chunk is independently processable — enables parallel fan-out
- **Calculator** processes chunks concurrently with bounded virtual threads
- **Synchronizer** reads result chunks and batch-upserts to PostgreSQL
- Failed chunks can be retried independently without re-processing the entire run

### Idempotency

- **Run ID:** Each pipeline execution gets a unique `runId` (e.g., `20260527-030001-123456789`)
- **Chunk ID:** `part-{NNNN}` — deterministic based on record order
- **Calculator skip logic:** If a result chunk already exists for the same `(runId, endpoint, chunkId)`, it's skipped (`result_exists` reason)
- **DB upsert:** PostgreSQL `ON CONFLICT ... DO UPDATE` ensures idempotent writes
- **Kafka consumer:** Offset-based with explicit ACK only after successful processing

### Replay & Retry

- **Kafka retry:** Messages that fail processing remain in the queue (no auto-ACK on error)
- **Visibility timeout:** Failed messages become visible again after a configured delay
- **Manual replay:** Re-publishing a chunk-ready event triggers reprocessing
- **Run-level replay:** Delete result artifacts for a specific runId, then re-trigger the pipeline

---

## Observability

### Prometheus Endpoints

| Module | Endpoint | Auth |
|--------|----------|------|
| external-api | `:8081/actuator/prometheus` | 401 (log-based metrics) |
| calculator | `:8082/actuator/prometheus` | Open |
| synchronizer | `:8083/actuator/prometheus` | Open |

### Key Metric Families

- **Calculator:** `calculator_users_processed_total`, `calculator_items_calculated_total`, `calculator_input_uncompressed_bytes_total`, `calculator_chunk_users_per_second`, `calculator_chunk_items_per_second`
- **Synchronizer:** `synchronizer_chunk_documents_count`, `synchronizer_chunk_duration_seconds`, `synchronizer_pre_upsert_json_rows_total`
- **JVM:** `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `process_cpu_usage`

### Grafana

Dashboard at `grafana/dashboard-pipeline.json`. Includes JVM, GC, throughput, and queue depth panels.

---

## Cleanup Lifecycle

```
External API creates artifact
        │
        ▼
Kafka: chunk-ready event
        │
        ▼
Calculator consumes → processes → writes result artifact
        │
        ▼
Kafka: result_chunk-ready event
        │
        ▼
Synchronizer consumes → upserts to PostgreSQL
        │
        ▼
Kafka: CHUNK_CONSUMED event (published by Synchronizer on success)
        │
        ▼
External API ConsumedChunkCleanupScheduler receives event
        │
        ▼
Virtual thread deletes: source chunk + result chunk files
```

- Cleanup runs on virtual threads for non-blocking I/O
- Events accumulate in an in-flight queue, drained hourly
- Failed deletions are logged but don't block the pipeline

---

## Infrastructure Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Runtime | Spring Boot 3.x / Kotlin | Application framework |
| Message Broker | Apache Kafka (CP 7.9) | Chunk-ready event choreography |
| Database | PostgreSQL 16 | Read models, OCID mappings |
| Cache (L1) | Caffeine | In-process response cache |
| Cache (L2) | PostgreSQL UNLOGGED tables | Cross-instance cache |
| Monitoring | Prometheus + Grafana | Metrics & dashboards |
| Log Aggregation | Loki (optional) | Centralized logging |
| Compression | gzip (JDK) | Artifact compression |
| Async I/O | Virtual Threads (JDK 21) | Non-blocking HTTP, file operations |
| Build | Gradle (Kotlin DSL) | Multi-module build |

---

## Future Evolution

### Short-term (Current Quarter)

- **MinIO / Object Storage:** Replace filesystem artifacts with S3-compatible storage. Enables multi-node deployment without shared NFS.
- **Redis distributed cache:** Already integrated for like feature. Extend to expectation cache for cross-instance invalidation.

### Mid-term

- **k3s / Kubernetes:** Container orchestration for auto-scaling calculator and synchronizer workers based on Kafka queue depth.
- **Docker Compose profiles:** Already implemented (`infra`, `app`, `observability`). Natural migration path to k3s.

### Long-term

- **Apache Spark:** For batch analytics over historical valuation data. Current JSONL.gz artifacts are already Spark-compatible.
- **Event sourcing:** Migrate from current "latest state" read model to full event log, enabling temporal queries (price history, trend analysis).
- **Streaming aggregation:** Replace periodic cron with Kafka Streams for continuous pipeline execution.
