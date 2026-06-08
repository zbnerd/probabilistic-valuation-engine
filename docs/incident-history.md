# Incident History & Postmortems

Operational incidents encountered during development and endurance testing of the Probabilistic Valuation Engine.

---

## Incident 1: Disk Exhaustion from Stale Chunks (143 GB)

**Date:** Pre-endurance-test
**Severity:** P1
**Duration:** Discovered during manual disk check
**Impact:** 143 GB of unconsumed chunk artifacts accumulated on disk

### Background

The External API module creates JSONL.gz chunk artifacts for each pipeline run. Calculator and Synchronizer consume these chunks via Kafka events. After consumption, the artifacts were supposed to be cleaned up — but no cleanup mechanism existed.

### Root Cause

No lifecycle management for consumed artifacts. Each daily run produced ~4 GB of source chunks. With item-equipment running every ~47 minutes, chunks accumulated faster than anyone monitored. Over multiple days without cleanup, 143 GB of stale files filled the data partition.

### Timeline

1. Pipeline running for several days without intervention
2. Disk usage alarm triggered at 90%+ utilization
3. Manual investigation found `data/runs/` consuming 143 GB
4. Emergency cleanup: manual `rm -rf` on old run directories

### Resolution

Implemented `ConsumedChunkCleanupScheduler` in `module-external-api`:

1. Synchronizer publishes `CHUNK_CONSUMED` Kafka event after successful DB upsert
2. Event carries `objectKey` (result chunk) and `sourceObjectKey` (source chunk)
3. External API consumes events and queues them for deletion
4. Hourly scheduler drains queue using virtual threads for parallel file deletion
5. Both source and result chunk files are deleted

### Lessons Learned

- **Every artifact needs a lifecycle.** Writing files without a deletion plan is a disk-fill incident waiting to happen.
- **Cleanup must be event-driven, not time-based.** cron-based "delete old files" approaches risk deleting in-flight data. Event-driven cleanup only deletes after confirmed consumption.
- **Monitor disk usage proactively.** Set alerts at 70% utilization, not 90%.

### Prevented in Endurance Test

The 82-hour endurance test confirmed: **216,209 files auto-deleted**, data directory oscillating between 9.7-21 GB with no unbounded growth.

---

## Incident 2: Double-Path Bug in Calculator Artifacts

**Date:** Pre-endurance-test
**Severity:** P2
**Impact:** Calculator result files saved to `data/data/calculator/...` instead of `calculator/...`

### Root Cause

Hardcoded path prefix `"data/calculator/runs/..."` in `CalculatorChunkProcessingCoordinator`. The base path was already `data/`, so the effective path became `data/data/calculator/runs/...`.

### Resolution

Removed `data/` prefix from path construction. Path now uses `"calculator/runs/{runId}/{endpoint}/chunks/result-{chunkId}.jsonl.gz"` which resolves correctly against the configured `base-path`.

### Lessons Learned

- **Never hardcode path prefixes.** Use configuration properties (`calculator.store.input-base-path`) consistently.
- **Test with non-default base paths.** The bug was masked when `base-path` was `../data` (creating `../data/data/...` which still worked, just incorrectly).

---

## Incident 3: Semaphore Permit Leak

**Date:** During earlier pipeline runs
**Severity:** P1
**Impact:** Pipeline stalls after semaphore permits exhausted

### Root Cause

Semaphore acquired in `try` block but released in success path only. Exception during processing caused permit to never be released. After N failures, all permits were exhausted and the pipeline deadlocked.

### Resolution

Wrapped all semaphore acquire/release in `LogicExecutor.executeWithFinally`:

```kotlin
executor.executeWithFinally(
    task = { semaphore.acquire(); doWork() },
    finalizer = { semaphore.release() },
    context = TaskContext("work-with-semaphore")
)
```

### Lessons Learned

- **`finally` is not optional for resource cleanup.** This exact pattern caused issues in 4+ separate PRs (#693, pgmq batchWrite, pipeline buffer).
- **Enforce via coding rules.** Project now mandates `LogicExecutor.executeWithFinally` for all resource acquisition patterns.

---

## Incident 4: Executor Pool / HikariCP Misalignment

**Date:** During load testing
**Severity:** P1
**Impact:** Thread starvation, connection pool exhaustion, cascading timeout failures

### Root Cause

`ThreadPoolTaskExecutor` with `maxPoolSize=50` backed by HikariCP `maximumPoolSize=10`. Under load, 50 threads competed for 10 DB connections, causing `ConnectionPoolTimeoutException` and request queuing.

### Resolution

Aligned executor sizing with HikariCP: `maxPoolSize` must be <= `maximumPoolSize`. All executors now explicitly declare `corePoolSize`, `maxPoolSize`, `queueCapacity`, and `threadNamePrefix` in YAML.

### Lessons Learned

- **Executor sizing is not independent of connection pooling.** Always pair them explicitly.
- **Never use `ForkJoinPool.commonPool()` for DB-bound work.** Default parallelism may exceed available connections.

---

## Incident 5: Spring Security Cascade in module-rest-controller

**Date:** During V6 like feature implementation
**Severity:** P2
**Impact:** All REST endpoints returned 401 Unauthorized

### Root Cause

`spring-boot-starter-security` was transitively included via `module-infra`. Auto-configuration activated `JwtAuthenticationFilter`, which required JPA-backed `UserDetailsService`. Module `rest-controller` had no JPA configured, causing startup failure then 401 on all endpoints.

### Resolution

Excluded security auto-configurations:

```kotlin
@SpringBootApplication(exclude = [
    SecurityAutoConfiguration::class,
    SecurityFilterAutoConfiguration::class,
    ManagementWebSecurityAutoConfiguration::class,
])
```

Replaced authentication with `X-Account-Id` header approach using direct JDBC queries.

### Lessons Learned

- **Transitive dependencies have side effects.** A module boundary should explicitly control which auto-configurations it enables.
- **`module-web → module-infra` direct dependency is prohibited** by hexagonal architecture rules. Module `rest-controller` needed a lighter approach.

---

## Incident 6: OOM During Multi-Module Concurrent Run

**Date:** Early pipeline development
**Severity:** P1
**Impact:** JVM OutOfMemoryError when running 3 modules on a single machine

### Root Cause

Default Spring Boot memory settings (`-Xmx` defaults to 1/4 of system memory). Three modules on a 16 GB machine each claimed ~4 GB heap, totaling ~12 GB + metaspace overhead, exceeding available RAM.

### Resolution

Explicit JVM memory limits: `-Xms512m -Xmx1g` per module. Total footprint: ~3 GB heap + ~0.7 GB native overhead = ~3.7 GB RSS.

### Lessons Learned

- **Always set explicit JVM memory limits** when running multiple JVMs on one host.
- **RSS != heap.** Plan for 30-40% overhead above `-Xmx` for metaspace, native buffers, and JIT compilation.

---

## Summary

| Incident | Severity | Category | Root Cause | Resolution |
|----------|----------|----------|------------|------------|
| Disk exhaustion | P1 | Resource leak | No artifact lifecycle | Event-driven cleanup |
| Double-path bug | P2 | Path config | Hardcoded prefix | Remove prefix, use config |
| Semaphore leak | P1 | Concurrency | Missing finally | LogicExecutor.executeWithFinally |
| Executor misalignment | P1 | Concurrency | Pool size mismatch | Align with HikariCP |
| Security cascade | P2 | Module boundary | Transitive dependency | Exclude auto-config |
| Multi-module OOM | P1 | Memory | Default JVM settings | Explicit -Xmx per module |

**Total incidents informing architecture:** 6
**Incidents that recurred:** Semaphore leak (4 times before rule enforced)
**Endurance test incidents:** 0 (all above were pre-test)
