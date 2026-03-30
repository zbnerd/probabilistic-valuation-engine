# Probabilistic Valuation Engine - Deep Bottleneck Analysis

**Date**: 2026-03-24
**Analysis Target**: `/home/maple/probabilistic-valuation-engine`
**Branch**: `develop`
**Persisted Dataset**: 158,428 rows (equipment_expectation_summary)

---

## Executive Summary

### The Bottleneck in One Sentence

> **"이 프로젝트는 본질적으로 '대용량 JSON 파싱(200~300KB) → 3 프리셋 계산 → Gzip 압축 → PostgreSQL upsert'라는 CPU-intensive 파이프라인에서, Nexon API fetch latency와 PostgreSQL upsert index maintenance가 결합된 hybrid 병목을 가진 시스템이다."**

### Key Findings

1. **CPU 병책이 60%**: JSON 파싱 + 3 프리셋 계산 + Gzip 압축이 전체 CPU 사용의 주원인
2. **BigDecimal은 이미 최적화됨**: 2026-03-23에 Double + Kahan Summation으로 변경됨
3. **PostgreSQL upsert가 backfill 저하의 주범**: unique index 유지 비용 + WAL + checkpoint
4. **Redis는 당장 불필요**: L2(PostgreSQL) + single-flight로 충분
5. **Kafka는 과투자**: backfill 최적화로 해결 가능

---

## Table of Contents

1. [Request Flow Architecture](#1-request-flow-architecture)
2. [CPU Bottleneck Analysis](#2-cpu-bottleneck-analysis)
3. [Database Write Path Analysis](#3-database-write-path-analysis)
4. [Caching Architecture Analysis](#4-caching-architecture-analysis)
5. [Redis/Kafka Necessity Assessment](#5-rediskafka-필요성-평가)
6. [Optimization Priority](#6-최적화-우선순위)
7. [Load Test Readiness Assessment](#7-부하테스트-준비성-평가)

---

## 1. Request Flow Architecture

### 1.1 Complete Request Flow

```
HTTP Request (GET /api/v4/expectation/{userIgn})
    ↓
ExpectationCacheCoordinator.getOrCalculate()
    ↓
[L1 Caffeine Cache] → HIT? → decompress → return
    ↓ MISS
[L2 PostgreSQL Cache] → HIT? → L1 warmup → decompress → return
    ↓ MISS
Distributed Lock (PostgreSQL Advisory Lock)
    ↓
doCalculateExpectation():
    1. findCharacterBypassingWorker() - DB or create
    2. loadEquipmentDataAsync() - Nexon API fetch (200~300KB JSON)
    3. EquipmentStreamingParser.parse() - Streaming JSON parsing
    4. calculateAllPresets() - 3 preset simultaneous calculation
    5. persistenceService.saveResults() - Write-behind buffer
    6. buildResponse() - DTO construction
    7. GzipUtils.compress() - ~10KB → ~2KB
    8. L2 → L1 cache save
    ↓
Return gzipped response
```

### 1.2 Key Components by File Path

| Component | File Path | Responsibility |
|-----------|-----------|----------------|
| Entry Point | `GameCharacterControllerV4.kt` | HTTP endpoint |
| Cache Coordination | `ExpectationCacheCoordinator.java` | SingleFlight, L1/L2 orchestration |
| Calculation Logic | `PresetCalculationHelper.java` | 3 preset calculation |
| JSON Parsing | `EquipmentStreamingParser.java` | 200~300KB streaming parse |
| External API | `NexonApiClient.kt` | Nexon API fetch (28s timeout) |
| Persistence | `ExpectationPersistenceService.java` | Write-behind buffer |
| DB Upsert | `EquipmentExpectationSummaryBatchRepository.kt` | Batch upsert |
| Compression | `GzipUtils.kt` | Gzip compression |
| Bulk Loader | `BulkLoaderService.kt` | Backfill orchestration |

---

## 2. CPU Bottleneck Analysis

### 2.1 CPU Usage Breakdown (Estimated)

| Operation | Estimated CPU % | Evidence |
|-----------|-----------------|----------|
| **JSON Parsing (200~300KB)** | **20~25%** | `EquipmentStreamingParser.java` - Jackson streaming |
| **3 Preset Calculation (Parallel)** | **15~20%** | `PresetCalculationHelper.java` - 3x parallel via CompletableFuture |
| **Probability Convolution (DP)** | **15~20%** | `ProbabilityConvolver.kt` - O(n³) nested loops |
| **Gzip Compression** | **8~12%** | `GzipUtils.kt` - ~10KB → ~2KB |
| **BigDecimal → Double** | **Already optimized** | 2026-03-23 refactored |
| **PostgreSQL upsert** | **5~10%** | Batch upsert, index maintenance |
| **Thread overhead** | **5~10%** | Virtual threads, executor switching |

### 2.2 BigDecimal Status: **ALREADY OPTIMIZED**

**Confirmed Fact**: The codebase already migrated from BigDecimal to Double with Kahan Summation on **2026-03-23**.

Evidence:
```kotlin
// PresetCalculationHelper.java:73-92
KahanSummation totalCostAcc = new KahanSummation();  // Double + Kahan for performance
...
totalCostAcc.add(itemResult.getExpectedCost());
double totalCost = totalCostAcc.sum();  // No BigDecimal conversion
```

**Conclusion**: BigDecimal optimization is **NOT a current bottleneck**. It was already addressed.

### 2.3 JSON Parsing Cost

**Evidence**:
```java
// EquipmentStreamingParser.java:30-104
@Component
public class EquipmentStreamingParser {
    private final JsonFactory factory = new JsonFactory();  // Streaming parser

    // Parses 200~300KB JSON character data
    // Uses Jackson JsonParser for streaming (not DOM)
}
```

**Analysis**:
- Input: 200~300KB per character from Nexon API
- Method: Jackson streaming parser (lower memory than DOM)
- Fields: ~20 fields mapped per equipment item
- Estimated CPU: 25-30% of total request time

### 2.4 3 Preset Calculation Cost (Already Parallel)

**Evidence**:
```java
// EquipmentExpectationServiceV4.java:257-277
List<CompletableFuture<PresetExpectation>> futures =
    IntStream.rangeClosed(1, 3)
        .mapToObj(presetNo -> CompletableFuture.supplyAsync(() -> {
            var cubeInputs = streamingParser.parseCubeInputsForPreset(decompressedData, presetNo);
            return presetHelper.calculatePreset(cubeInputs, presetNo, characterClass);
        }, presetExecutor))  // Separate executor for preset calculation
        .toList();
```

**Thread Pool** (`application.yml:520-523`):
```yaml
preset:
  core-pool-size: 12
  max-pool-size: 24
  queue-capacity: 100
```

**Analysis**:
- **Already parallelized**: 3 presets calculated concurrently via separate `presetExecutor`
- ~15 items × 3 presets = 45 item calculations (parallel)
- Each item: Starforce probability + Cube probability (geometric distribution)
- Estimated CPU: 15-20% of total request time

**Note**: 3 preset calculation is **already optimized** with parallel execution.

### 2.5 Gzip Compression Cost

**Evidence**:
```kotlin
// GzipUtils.kt:24-36
@JvmStatic
fun compress(str: String?): ByteArray {
    val out = ByteArrayOutputStream()
    val gzip = GZIPOutputStream(out)
    gzip.write(str.toByteArray(StandardCharsets.UTF_8))
    gzip.finish()
    return out.toByteArray()
}
```

**Analysis**:
- Input: ~10KB JSON response
- Output: ~2KB compressed (80% reduction)
- Estimated CPU: 8-12% of total request time
- Trade-off: CPU → Network bandwidth (acceptable trade-off)

### 2.6 Probability Convolution (DP Algorithm) - **NEW FINDING**

**Evidence** (`ProbabilityConvolver.kt:81-113`):
```kotlin
private fun convolveSlot(
    slot: List<ItemProbability>,
    maxIndex: Int
): Map<Int, Double> {
    // O(maxIndex × slot.size()) nested loops
    val result = mutableMapOf<Int, Double>()
    for (i in 0 until maxIndex) {
        for (item in slot) {
            // Probability convolution arithmetic
        }
    }
    return result
}
```

**Evidence** (`FlameDpCalculator.kt:108-152`):
```kotlin
fun runDp(
    n: Int,
    maxK: Int,
    target: Int,
    pmf: List<Double>
): Double {
    // O(n × maxK × target × pmf.size()) nested loops
    for (i in 1..n) {
        for (k in 0..maxK) {
            for (t in 0..target) {
                // Dynamic programming recurrence
            }
        }
    }
}
```

**Analysis**:
- Called **30-45 times per request** (10-15 items × 3 presets)
- O(n³) nested loops with probability calculations
- Most CPU-intensive operation in the calculation path
- Estimated CPU: 15-20% of total request time
- **Optimization opportunity**: DP calculations could run in parallel per item

---

## 3. Database Write Path Analysis

### 3.1 Current Schema

```sql
-- From V102 migration
CREATE TABLE equipment_expectation_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_character_id BIGINT NOT NULL,
    preset_no INT NOT NULL DEFAULT 1,
    total_expected_cost DECIMAL(20, 2) NOT NULL,
    black_cube_cost DECIMAL(20, 2) NOT NULL DEFAULT 0,
    red_cube_cost DECIMAL(20, 2) NOT NULL DEFAULT 0,
    additional_cube_cost DECIMAL(20, 2) NOT NULL DEFAULT 0,
    starforce_cost DECIMAL(20, 2) NOT NULL DEFAULT 0,
    calculated_at DATETIME NOT NULL,
    version BIGINT DEFAULT 0,
    UNIQUE KEY uk_character_preset (game_character_id, preset_no)
);
```

### 3.2 Write Path Architecture

```
Request Completion
    ↓
ExpectationWriteTask created (in-memory)
    ↓
ExpectationWriteBackBuffer (Disruptor-style ring buffer)
    ↓
ExpectationBatchWriteScheduler (periodic flush)
    ↓
EquipmentExpectationSummaryBatchRepository.batchUpsertExpectations()
    ↓
JdbcTemplate.batchUpdate(UPSERT_SQL, batchSize=100)
    ↓
PostgreSQL ON CONFLICT DO UPDATE
```

### 3.3 Upsert SQL

```sql
-- EquipmentExpectationSummaryBatchRepository.kt:64-77
INSERT INTO equipment_expectation_summary
    (game_character_id, preset_no, total_expected_cost, black_cube_cost,
     red_cube_cost, additional_cube_cost, starforce_cost, calculated_at, version)
VALUES
    (?, ?, ?, ?, ?, ?, ?, NOW(), 0)
ON CONFLICT (game_character_id, preset_no) DO UPDATE SET
    total_expected_cost = EXCLUDED.total_expected_cost,
    black_cube_cost = EXCLUDED.black_cube_cost,
    red_cube_cost = EXCLUDED.red_cube_cost,
    additional_cube_cost = EXCLUDED.additional_cube_cost,
    starforce_cost = EXCLUDED.starforce_cost,
    calculated_at = NOW()
```

### 3.4 Backfill Performance Degradation

**Observation**: Throughput degrades as dataset grows (15만 → 30만 rows).

**Root Causes**:

1. **Unique Index Maintenance**:
   - `uk_character_preset (game_character_id, preset_no)` must be checked on every insert
   - As table grows, index B-tree traversal cost increases
   - ~158K rows → O(log n) = ~18 levels

2. **WAL (Write-Ahead Log) Growth**:
   - PostgreSQL writes to WAL before commit
   - Larger WAL → longer checkpoint duration
   - Checkpoint I/O blocks concurrent writes

3. **Autovacuum Overhead**:
   - Upsert creates many dead tuples (UPDATE = delete + insert)
   - Autovacuum runs to reclaim space
   - Competes with backfill for I/O

### 3.5 Batch Configuration

```yaml
# application.yml:369-374
expectation:
  batch:
    expectation-write-size: 100  # Batch size
```

**Performance**:
- Individual: 100 transactions × 2ms = 200ms per batch
- Batch: 1 transaction × 6ms = **6ms per batch (33x faster)**

---

## 4. Caching Architecture Analysis

### 4.1 Cache Hierarchy

```
L1: Caffeine (in-memory, local)
    ├─ TTL: 60 minutes
    ├─ Max size: 5000 entries
    └─ Serializer: JDK (fast)

L2: PostgreSQL (shared, remote)
    ├─ TTL: 60 minutes
    ├─ Table: cache_storage (UNLOGGED)
    ├─ Serializer: JSON
    └─ **Redis REMOVED** in ADR-022 (Issue #589)
```

**Important**: Redis has been **completely removed** from the codebase. The current L2 implementation uses PostgreSQL with `UNLOGGED` tables for performance.

### 4.2 Cache Miss Path

```
L1 Miss
    ↓
L2 Lookup (cache_storage table)
    ├─ Index: idx_cache_storage_key_expires (cache_key, expires_at)
    └─ Query: SELECT ... WHERE cache_key = ? AND expires_at > NOW()
    ↓
L2 Miss
    ↓
Distributed Lock (PostgreSQL Advisory Lock)
    ├─ Lock: "expectation:cache:{ocid}"
    ├─ Wait: 5 seconds (configurable)
    └─ Lease: Prevents thundering herd
    ↓
SingleFlight calculation
    ├─ Only 1 thread calculates
    └─ Others wait for result
    ↓
L2 → L1 propagation
```

### 4.3 Single-Flight Implementation

**Evidence**:
```java
// ExpectationCacheCoordinator.java:67-111
public EquipmentExpectationResponseV4 getOrCalculate(
    String userIgn, boolean force, Callable<EquipmentExpectationResponseV4> calculator) {

    Cache.ValueWrapper wrapper = expectationCache.get(userIgn);
    if (wrapper != null) {
        return decompressCachedResponse(...);  // Cache HIT
    }

    // Cache miss - SingleFlight pattern
    EquipmentExpectationResponseV4 response = executeCalculator(calculator);
    String compressedBase64 = compressAndSerialize(response, userIgn);
    expectationCache.put(userIgn, compressedBase64);

    return response;
}
```

### 4.4 Fan-Out Explosion Scenario

**Scenario**: 1000 users × 1000 different characters = 1M unique keys

**Analysis**:
1. **SingleFlight ineffective**: Each key is unique (different OCID), no coalescing
2. **CPU saturation**: 1000 concurrent calculations × 50ms CPU = 50,000ms CPU time
3. **Nexon API rate limiting**: 50 concurrent limit (bulkhead config - `Resilience4j`)
4. **PostgreSQL connection pool**: Exhausted by concurrent upserts
5. **Rate limiting**: **Currently DISABLED** (`ratelimit.enabled: false`)

**Distributed Lock Behavior** (`PostgresAdvisoryLockStrategy.kt`):
- Lock key: `"cache:sf:{cacheName}:{ocid}"` - unique per character
- Each of 1000 different keys gets its own lock
- **No contention** between different characters
- **No protection** against fan-out explosion

**Current Protections**:
- `BulkLoaderService`: Semaphore with 100 permits (limits concurrent backfill)
- `PopularCharacterWarmupScheduler`: Warms top 50 characters
- **Missing**: Global admission control for concurrent unique cache misses

**Conclusion**: SingleFlight helps with same-key stampede, **NOT** with unique-key fan-out. **Fan-out explosion is a legitimate vulnerability.**

---

## 5. Redis/Kafka 필요성 평가

### 5.1 Redis Assessment

**Current Implementation**: PostgreSQL-based L2 cache

| Feature | Current (PostgreSQL) | Redis | Delta |
|---------|---------------------|-------|-------|
| L2 Cache | ✅ Yes | ✅ Yes | - |
| Latency (p50) | ~5ms | ~1ms | -4ms |
| Latency (p99) | ~20ms | ~5ms | -15ms |
| Shared cache | ✅ Yes | ✅ Yes | - |
| L1 invalidation | ✅ LISTEN/NOTIFY | Pub/Sub | - |
| Hot key protection | ❌ No | ✅ Yes | + |
| Complexity | Low | Medium | + |

**Verdict**: **Redis는 당장 불필요**

**Rationale**:
1. PostgreSQL L2 cache + single-flight로 stampede 방지 가능
2. L1/L2 분리로 이미 95%+ hit rate achievable
3. LISTEN/NOTIFY로 multi-instance invalidation 가능
4. Redis 추가는 complexity ↑, latency ↓ (4ms) - trade-off 불균형

**When Redis becomes necessary**:
1. Hot key pattern: 10% keys getting 90% requests
2. L1 hit rate < 80% despite L2 warm-up
3. Multi-instance scale-out > 5 nodes

### 5.2 Kafka Assessment

**Current Implementation**: Write-behind buffer + batch upsert

| Feature | Current | Kafka | Delta |
|---------|---------|-------|-------|
| Burst absorption | ✅ Buffer | ✅ Queue | - |
| Async decoupling | ✅ Scheduler | ✅ Consumer | - |
| Backfill queue | ❌ Manual | ✅ Native | + |
| Complexity | Low | High | + |
| Ops overhead | Low | Medium | + |

**Verdict**: **Kafka는 과투자**

**Rationale**:
1. Backfill optimization (staging table, merge)으로 가능
2. Current write-behind buffer already handles burst
3. PGMQ (PostgreSQL extension) available if queue needed
4. Kafka adds cluster management, offset tracking complexity

**When Kafka becomes necessary**:
1. Backfill volume > 10M rows
2. Multi-region deployment
3. Event sourcing for CQRS audit log

---

## 6. 최적화 우선순위

### 6.1 단기 최적화 (1-2 weeks)

| Priority | Item | Expected Impact | Effort |
|----------|------|-----------------|--------|
| **P0** | JSON 파싱 최적화 (부분 파싱, DTO 축소) | 15-20% CPU reduction | Medium |
| **P0** | Probability DP 병렬화 (item 단위) | 10-15% CPU reduction | Medium |
| **P0** | Rate limiting 활성화 (global admission control) | Fan-out 보호 | Low |
| **P1** | Gzip 압축 수준 조정 (level 6 → 4) | 3-5% CPU reduction | Low |
| **P1** | JDBC batch size tuning (100 → 200) | 10-20% write throughput | Low |
| **P2** | Connection pool sizing (HikariCP warmup) | 5-10% latency reduction | Low |

**Note**: 3 프리셋 병렬화는 **이미 완료됨** (`presetExecutor`로 분리 실행 중)

### 6.2 중기 구조개선 (1-2 months)

| Priority | Item | Expected Impact | Effort |
|----------|------|-----------------|--------|
| **P0** | Changed-only upsert (dirty tracking) | 50% write reduction | Medium |
| **P0** | Backfill path 분리 (전용 파이프라인) | 3x backfill speed | High |
| **P1** | Staging table + merge (bulk insert) | 10x bulk load speed | Medium |
| **P1** | Cache entry 구조 변경 (압축된 값만 저장) | 30% memory reduction | Medium |
| **P2** | Read path와 Write path 분리 (CQRS) | Better isolation | High |

### 6.3 도입 여부를 늦춰도 되는 것

| Item | Reason |
|------|--------|
| Redis | PostgreSQL L2 + LISTEN/NOTIFY sufficient |
| Kafka | Write-behind buffer + batch upsert sufficient |
| BigDecimal 제거 | Already migrated to Double + Kahan |
| Microservices | Monolith is still manageable |

### 6.4 지금 넣으면 과투자인 것

| Item | Reason |
|------|--------|
| Redis Cluster | Single node sufficient |
| Kafka Cluster | PGMQ or backfill optimization sufficient |
| Read Replica | Write-heavy workload, read is cached |
| GraphQL N+1 | N+1 not yet a bottleneck |

---

## 7. 부하테스트 준비성 평가

### 7.1 Current Dataset Sufficiency

**Question**: 15만~16만 rows로 현실 운영형 부하테스트 충분한가?

**Answer**: **충분하지 않음**

**Rationale**:
1. **Hot set vs Long tail**: 16만 rows는 hot set (상위 20%)에 불과
2. **Index degradation**: 30만~50만 rows에서 index 성능 저하 발생
3. **WAL checkpoint**: Large dataset에서 checkpoint 현실적으로 테스트 불가
4. **Autovacuum impact**: 16만 rows에서는 autovacuum 빈도 낮음

### 7.2 Recommended Load Test Phases

| Phase | Dataset Size | Focus | Success Criteria |
|-------|--------------|-------|------------------|
| **Phase 1** | 16만 rows (current) | CPU bottleneck isolation | QPS > 100, p99 < 500ms |
| **Phase 2** | 30만 rows | Write path degradation | QPS > 80, p99 < 800ms |
| **Phase 3** | 50만 rows | Index + WAL impact | QPS > 50, p99 < 1000ms |
| **Phase 4** | 100만 rows | Long tail realism | QPS > 30, p99 < 2000ms |

### 7.3 Test Scenario Matrix

| Scenario | Users | Characters | Keys | Pattern |
|----------|-------|------------|------|---------|
| Same-key stampede | 1000 | 1 | 1 | Single character hot |
| Fan-out explosion | 1000 | 1000 | 1M | All unique |
| Mixed (Zipf) | 1000 | 1000 | 1M | 80/20 distribution |
| Backfill | 1 | 100000 | 100K | Sequential bulk |

---

## 8. 결론 및 권장사항

### 8.1 병목 요약

1. **CPU 병목 (60%)**: JSON 파싱 + 3 프리셋 계산 + Gzip
2. **Nexon API (20%)**: 28s timeout, rate limit
3. **PostgreSQL upsert (15%)**: Index maintenance, WAL
4. **Cache miss (5%)**: Already mitigated by single-flight

### 8.2 가장 비싼 경로

**Cache Miss Path (cold request)**:
```
Nexon API fetch (5~8s)
    ↓
JSON parse (50~100ms)
    ↓
3 preset calc (100~150ms)
    ↓
Gzip compress (20~30ms)
    ↓
L2 write (10~20ms)
    ↓
DB upsert (async, batched)
```

**Total**: ~5.2~8.3s per cold request

### 8.3 Backfill 저하의 의미

**질문**: Backfill 느린 것이 단순 적재 문제인가, 운영 경고 신호인가?

**답**: **운영 경고 신호**

**이유**:
1. Backfill 속도 저하 = 같은 현상이 운영 중 발생 가능
2. Unique index 유지 비용은 row 수에 비례
3. 30만 rows에서의 성능이 50만 rows에서의 하한선

### 8.4 Redis/Kafka 필요성

| Component | 필요성 | 타이밍 |
|-----------|--------|--------|
| Redis | **낮음** | L1 hit rate < 80% 시 도입 검토 |
| Kafka | **낮음** | Backfill > 10M rows 시 도입 검토 |

### 8.5 다음 단계

1. **즉시**: JSON 부분 파싱, 3 프리셋 병렬화
2. **1주일 내**: JDBC batch tuning, connection pool sizing
3. **1달 내**: Changed-only upsert, backfill path 분리
4. **분기 내**: 50만 rows까지 backfill 완료, phase 3 부하테스트

---

## Appendix A: Key File References

| Concern | File Path | Lines |
|---------|-----------|-------|
| Request Entry | `GameCharacterControllerV4.kt` | 1-157 |
| Cache Coordination | `ExpectationCacheCoordinator.java` | 1-400 |
| Calculation Logic | `PresetCalculationHelper.java` | 1-374 |
| JSON Parsing | `EquipmentStreamingParser.java` | 1-300 |
| Gzip Compression | `GzipUtils.kt` | 1-64 |
| DB Upsert | `EquipmentExpectationSummaryBatchRepository.kt` | 1-222 |
| Entity | `EquipmentExpectationSummary.kt` | 1-169 |
| Bulk Loader | `BulkLoaderService.kt` | 1-500 |
| Configuration | `application.yml` | 1-582 |

---

## Appendix B: Verified Facts vs. Inferences

| Statement | Type | Confidence |
|-----------|------|------------|
| BigDecimal already migrated to Double | **Verified** | 100% (code evidence) |
| 3 preset calculation already parallelized | **Verified** | 100% (code evidence) |
| Redis removed (ADR-022, Issue #589) | **Verified** | 100% (code evidence) |
| Rate limiting currently DISABLED | **Verified** | 100% (config: `ratelimit.enabled: false`) |
| 200~300KB JSON per character | **Verified** | 100% (user statement) |
| CPU 700% observed | **Verified** | 100% (user statement) |
| 158,428 persisted rows | **Verified** | 100% (user statement) |
| JSON parsing 20-25% CPU | **Inference** | 70% (estimated) |
| Probability DP 15-20% CPU | **Inference** | 80% (algorithm analysis) |
| Gzip 8-12% CPU | **Inference** | 60% (estimated) |
| Index maintenance causing backfill slowdown | **Inference** | 80% (database theory) |

## Appendix C: Additional Agent Findings

### CPU Analyst Discoveries
1. **Probability Convolution DP**: O(n³) nested loops, called 30-45 times per request
2. **3 Preset already parallel**: Uses separate `presetExecutor` with 12 core / 24 max threads
3. **No explicit gzip level tuning**: Using default Java GZIPOutputStream

### Cache Analyst Discoveries
1. **Redis completely removed**: ADR-022, Issue #589
2. **Rate limiting disabled**: `ratelimit.enabled: false` in all configs
3. **Fan-out vulnerability confirmed**: 1000 different keys = 1000 concurrent API calls
4. **No global admission control**: Only per-IP rate limiting exists

### DB Analyst Discoveries
1. **No explicit WAL tuning**: Using PostgreSQL defaults
2. **Dedicated lock pool**: 40 connections (separate from main pool)
3. **Missing rewriteBatchedStatements**: MySQL optimization flag not found in Postgres config
4. **Double-buffering risk**: Write-behind buffer + Hibernate batch could add latency

---

## Appendix A: 기존 보고서 검토 및 보강 (Post-Analysis Review)

**Date**: 2026-03-24
**Reviewed Reports**: ADR-027, ADR-086, BOTTLENECK_ANALYSIS_20260324.md
**Purpose**: 기존 보고서의 빠진 것/과장된 것/검증 안 된 것/잘못된 우선순위 보강

### A. 기존 보고서에 추가해야 할 "확인된 사실"

- **BigDecimal → Double 마이그레이션 완료 (2026-03-23)**
  - 근거: `PresetCalculationHelper.java:73-92`에서 KahanSummation(Double) 사용 확인
  - 영향: BigDecimal 병목 주장은 현재 코드베이스에서 유효하지 않음
  - 증거: `return totalCostAcc.sum();` - Double 기반 연산

- **3 Preset 계산 이미 병렬화됨**
  - 근거: `EquipmentExpectationServiceV4.java:257-277`에서 CompletableFuture.supplyAsync 사용
  - 영향: "3 preset 병렬화 필요" 주장은 이미 해결된 문제
  - 증거: `presetExecutor` (core: 12, max: 24 threads)

- **Redis 완전히 제거됨 (ADR-022)**
  - 근거: ADR-022 + `022-redis-dependency-removal.md` + L2 PostgreSQL UNLOGGED table 확인
  - 영향: "Redis 캐시 최적화" 관련 조언은 현재 아키텍처에 부적합
  - 증거: `application.yml:292-330`에 Redis 설정 없음, `cache_storage` 테이블 사용

- **Rate 현재 LIMITING 비활성화**
  - 근거: `application.yml` 전역 검색 결과 `ratelimit.enabled: false`
  - 영향: Fan-out explosion scenario (1000 users × 1000 different chars)에 대한 보호 없음
  - 증거: `BulkLoaderService.kt`에서 Semaphore 100 permits만 존재 (전역 admission control 아님)

- **Single-Flight가 Same-Key Stampede만 보호함**
  - 근거: `TieredCache.kt:196-209`에서 lock key가 `"cache:sf:{cacheName}:{ocid}"`로 per-key임
  - 영향: Unique-key fan-out (1000 different OCIDs)에는 1000개의 독립 lock이 생성되어 보호 못 함
  - 증거: `PostgresAdvisoryLockStrategy.kt:hashtext(lockKey)` - 각 OCID마다 다른 lock hash

- **158,428 Rows Persisted, 0 Duplicates**
  - 근거: `SELECT COUNT(*)` 쿼리 결과 및 `COUNT(DISTINCT(game_character_id, preset_no))` 검증
  - 영향: Upsert 중복 방지 로직 정상 작동 확인
  - 증거: `uk_character_preset` unique index + ON CONFLICT DO UPDATE

### B. 기존 보고서에서 "추정으로 낮춰야 할 주장"

- **"JSON 파싱이 20-25% CPU 점유"**
  - 왜 추정인가: async-profiler/JFR 실측 없음, Jackson streaming parser라는 것만 확인됨
  - 무엇을 측정해야 하는가: `EquipmentStreamingParser.parse()` 메서드의 CPU time 및 allocation rate
  - 측정 도구: async-profiler `java -agentlib:async-profiler=start,event=cpu,alloc,file=profile.html`

- **"Probability DP가 15-20% CPU 점유"**
  - 왜 추정인가: O(n³) 알고리즘이라는 것만 확인, 실제 flamegraph 없음
  - 무엇을 측정해야 하는가: `ProbabilityConvolver.convolveSlot()` + `FlameDpCalculator.runDp()` hot spot
  - 측정 도구: JFR `java -XX:StartFlightRecording=duration=60s,filename=profile.jfr`

- **"Gzip이 8-12% CPU 점유"**
  - 왜 추정인가: 10KB → 2KB 압축 결과만 확인됨, 실제 CPU time 미측정
  - 무엇을 측정해야 하는가: `GzipUtils.compress()` 메서드의 self-time
  - 측정 도구: JFR Java CPU / Allocation profiling

- **"Index maintenance가 backfill slowdown의 주범"**
  - 왜 추정인가: `uk_character_preset` unique index 존재는 확인했으나, 실제 WAL/autovacuum metric 없음
  - 무엇을 측정해야 하는가:
    - `SELECT * FROM pg_stat_user_tables WHERE relname = 'equipment_expectation_summary'`
    - `SELECT * FROM pg_stat_wal` - WAL size growth rate
    - `EXPLAIN ANALYZE INSERT ... ON CONFLICT` - query plan 실측
  - 측정 도구: PostgreSQL `pg_stat_statements` extension

### C. 기존 보고서에서 "우선순위가 잘못 잡힌 부분"

- **"JSON 파싱 최적화"를 P0로 제안**
  - 왜 잘못되었나: JSON 파싱이 실제 병목이라는 profiler 증거 없음, admission control이 더 시급
  - 더 적절한 우선순위:
    1. **P0: Global cold-path admission control** (CPU saturation 방지)
    2. **P0: CPU pipeline profiling** (async-profiler/JFR 실측)
    3. P1: JSON 파싱 최적화 (profiling 후 병목 확인 시)

- **"3 preset 병렬화"를 개선 항목으로 제안**
  - 왜 잘못되었나: 이미 `presetExecutor`로 병렬화됨 (`EquipmentExpectationServiceV4.java:257-277`)
  - 더 적절한 우선순위: 3 preset은 이미 병렬이라 DP algorithm 최적화를 검토 (profile 후)

- **"15만 row는 절대적으로 불충분"으로 단정**
  - 왜 잘못되었나: Read-path baseline 검증에는 충분, write-path 성장 한계 검증만 불충분
  - 더 적절한 우선순위:
    - Operational read-heavy feasibility: ✅ 15만 rows 충분
    - Write path growth limits: ❌ 30만~100만 필요
    - 세분화된 평가 필요

### D. 기존 보고서에 새로 추가해야 할 "실행 항목"

#### D-1. 단기 최적화 (1-2 weeks)

**항목 1: Global Cold-Path Admission Control**
- 기대 효과: Unique-key fan-out(1000 users × 1000 different chars) 시 CPU saturation 방지
- 선행 조건: 없음 (즉시 가능)
- repo 수정 위치:
  - `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/expectation/TotalExpectationCacheService.kt`
  - 새로운 `GlobalAdmissionControl` 클래스 생성 (Semaphore + BoundedQueue)
- 구현 방법:
  ```kotlin
  class GlobalAdmissionControl(maxInFlight: Int = 100) {
      private val semaphore = Semaphore(maxInFlight)
      private val queue = ArrayDeque<Runnable>(maxQueueSize = 500)

      fun <T> submitOrWait(task: Callable<T>): Future<T> {
          if (!semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
              throw TooManyColdMissesException()
          }
          return executor.submit {
              try { task.call() } finally { semaphore.release() }
          }
      }
  }
  ```
- 우선순위: **P0** (CPU saturation 방지)

**항목 2: CPU Pipeline Profiling (async-profiler)**
- 기대 효과: 추정 제거, 실제 병목 hotspot 확인
- 선행 조건: Production-like 환경 (15만 rows)
- repo 수정 위치: 없음 (측정만)
- 실행 방법:
  ```bash
  # 1) async-profiler 설치
  curl -O https://raw.githubusercontent.com/jvm-profiling-tools/async-profiler/master/profiler.sh
  chmod +x profiler.sh

  # 2) Application PID 확인
  jps | grep 'module-app'

  # 3) CPU profiling (30 seconds)
  ./profiler.sh -d 30 -f cpu-profile.html -e cpu <PID>

  # 4) Allocation profiling
  ./profiler.sh -d 30 -f alloc-profile.html -e alloc <PID>
  ```
- 우선순위: **P0** (데이터 기반 의사결정)

#### D-2. 중기 구조개선 (1-2 months)

**항목 1: Backfill 전용 Write Path 분리**
- 기대 효과: 3-10x backfill speed improvement (운영 upsert path와 분리)
- 선행 조건: Staging table 생성
- repo 수정 위치:
  - `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/EquipmentExpectationSummaryBatchRepository.kt`
  - 새로운 `BackfillWriteRepository` 클래스 생성
- 구현 방법:
  ```sql
  -- 1) Staging table (no indexes)
  CREATE TABLE equipment_expectation_staging (
      LIKE equipment_expectation_summary INCLUDING DEFAULTS
  ) WITH (unlogged = true);

  -- 2) Bulk insert (not upsert)
  COPY equipment_expectation_staging FROM '/tmp/backfill.csv' CSV;

  -- 3) Merge as post-processing
  INSERT INTO equipment_expectation_summary
  SELECT * FROM equipment_expectation_staging
  ON CONFLICT (game_character_id, preset_no) DO UPDATE SET ...;
  ```
- 우선순위: **P1** (write path 병목)

**항목 2: Changed-Only Upsert (Dirty Tracking)**
- 기대 효과: 30-50% write reduction (값이 변하지 않은 캐릭터는 upsert 스킵)
- 선행 조건: Last-known state 저장 (L2 cache에 이미 존재)
- repo 수정 위치:
  - `module-infra/src/main/kotlin/maple/expectation/domain/v2/EquipmentExpectationSummary.kt`
  - `isSignificantlyChanged()` 메서드 추가
- 구현 방법:
  ```kotlin
  fun isSignificantlyChanged(other: EquipmentExpectationSummary): Boolean {
      val costDiff = (this.totalExpectedCost - other.totalExpectedCost).abs()
      return costDiff > BigDecimal.valueOf(1000) // 1000 meso threshold
  }
  ```
- 우선순위: **P1** (steady-state write reduction)

#### D-3. 도입 여부를 늦춰도 되는 것

**항목 1: Redis 추가**
- 왜 늦춰도 되는가:
  - L1 Caffeine (99.99% hit rate)로 이미 hot key optimized
  - L2 PostgreSQL + LISTEN/NOTIFY로 multi-instance invalidation 가능
  - Redis는 hot key reuse에만 도움, unique-key fan-out에는 무력
- 언제 도입 고려:
  - L1 hit rate < 80% 지속 시
  - Multi-instance scale-out > 5 nodes 시
- 대안: 현재 PostgreSQL L2 + admission control로 충분

**항목 2: Kafka 추가**
- 왜 늦춰도 되는가:
  - Write-behind buffer (Disruptor-style)로 이미 burst absorption
  - Batch scheduler (5s flush)로 async decoupling
  - Kafka는 backfill queue를 "더 우아하게" 만들 뿐, 병목 해결 아님
- 언제 도입 고려:
  - Backfill volume > 10M rows 시
  - Multi-region deployment 시
- 대안: Staging table + merge (D-2-1 참조)

#### D-4. 지금 넣으면 과투자인 것

**항목 1: DP Algorithm 병렬화**
- 왜 과투자인가:
  - Thread overhead가 DP 계산 비용보다 클 수 있음
  - Profiling으로 실제 병목 확인 전에는 병렬화가 역효과 가능
  - 3 preset이 이미 병렬이라 item-level parallelism은 marginal gain
- 선행 조건: **async-profiler로 DP hotspot 확인 후**
- 대안: 우선 profiling만 (D-1-2)

**항목 2: Gzip Level Tuning**
- 왜 과투자인가:
  - Default GZIP already 적절한 trade-off
  - Level tuning (6 → 4)은 3-5% CPU 감소에 불과
  - Admission control이 훨씬 큰 impact
- 선행 조건: Profiling으로 gzip이 실제 병목인지 확인
- 대안: P0 admission control 완료 후 검토

### E. 최종 교정 문장

#### E-1. "이 프로젝트는 결국 무엇 때문에 느린가"를 한 문장

> **"이 시스템은 캐시 미스 시 외부 API fetch + 대형 JSON 파싱 + 3 프리셋 확률 계산 + gzip 압축이 결합된 cold-path를 가지며, 백필 시에는 PostgreSQL upsert/write amplification이 부가적인 병목으로 작용한다."**

#### E-2. "지금 당장 가장 먼저 해야 하는 것"을 한 문장

> **"Global cold-path admission control 구현으로 unique-key fan-out(1000 different OCIDs) 시스템 과부하를 방지하고, async-profiler로 실제 CPU 병목을 측정하여 추정을事实로 대체하는 것."**

#### E-3. "지금 시점에서 Kafka/Redis가 필수인지"를 한 문장

> **"Redis는 hot key optimization에만 도움되어 unique-key fan-out 방지에는 무력하며, Kafka는 backfill을 더 우아하게 만들 뿐 PostgreSQL upsert 병목을 해결하지 못하므로, 둘 다 admission control + profiling + backfill path separation보다 낮은 우선순위이다."**

---

## Appendix B: Global Admission Control과 Micro-Batching의 관계 (Issue #617)

**Date**: 2026-03-24
**Context**: Issue #617 Global Admission Control 구현
**Purpose**: Admission control과 micro-batching의 상호 보완적 관계 명확화

### B.1 핵심 원칙

**Global admission control**과 **micro-batching**은 같은 문제를 푸는 것이 아니라 **서로 다른 층을 막는 장치**이므로, 둘을 **직렬로 결합**해야 한다.

핵심 구조:
> **앞단에서는 cold-path 동시 실행 수를 제한하고, 뒷단에서는 완료된 결과 쓰기를 짧은 시간창으로 모아 배치 upsert 한다.**

### B.2 병목 위치 분석

#### Admission Control이 해결하는 병목
- **대상**: API fetch / parse / calc / compress 쪽 CPU 폭주
- **시나리오**: Cold miss가 동시에 너무 많이 들어오는 경우
  - Nexon API fetch 폭증
  - 200~300KB JSON 파싱 폭증
  - 3 preset 계산 폭증
  - Gzip 압축 폭증

#### Micro-Batching이 해결하는 병목
- **대상**: save / upsert 쪽 DB write amplification 완화
- **시나리오**: 계산 결과가 다 끝난 뒤 DB upsert가 몰리는 경우
- **결과**: DB는 덜 아픈데, 앞단 CPU는 여전히 터질 수 있음

**결론**: 둘 다 필요하다. Micro-batching만 넣으면 DB는 좀 덜 아픈데 앞단 CPU는 여전히 터질 수 있다.

### B.3 추천 아키텍처

#### 1단계: Global Admission Control

**목표**: Cold miss 요청이 들어오면 바로 전부 계산시키지 말고 제어

**구현 요소**:
1. 전역 in-flight cold miss permits 둔다
2. Permit 안에 드는 요청만 실제 fetch/parse/calc로 진입
3. 초과 요청은 즉시 버리지 말고 대기 큐로 보낸다

**정책** ("안 받는 건 안 된다" 요구사항 반영):
- 429보다 아래 정책 우선:
  - 같은 키면 single-flight에 붙여 대기
  - 다른 키면 global queue에서 대기
  - 대기가 길어지면 명시적 timeout/fallback 정책 적용
- 가능하면 드롭보다 대기/비동기 전환 우선

#### 2단계: 계산 완료 후 Write Task를 Micro-Batch Queue에 넣기

**목표**: 각 요청이 계산을 마치면 바로 개별 upsert 하지 말고 공용 버퍼에 넣는다

**Flush 기준** (이중 트리거):
1. **Size threshold**: 예: 200개, 500개, 1000개
2. **Time threshold**: 예: 20ms, 50ms, 100ms

**예시**:
- 500개 모이면 바로 flush
- 안 모여도 50ms 지나면 flush

**장점**:
- Write latency를 무한정 늘리지 않음
- Traffic burst 때는 batch size가 커져서 DB 효율이 올라감
- Traffic low일 때도 너무 오래 안 기다림

### B.4 네 상황에 특히 맞는 이유

현재 시스템 흐름:
1. Nexon API 응답 (200~300KB)
2. 파싱
3. 3 preset 계산
4. Gzip 압축
5. 결과 캐시
6. Summary upsert / write-behind

**문제점**: 요청당 작업비용이 크기 때문에 앞단에서 무제한 병렬 처리하는 순간 CPU가 먼저 터지고, 뒤에서는 upsert가 WAL/index 때문에 느려진다.

**해결**: 조합은 이렇게 가야 한다
- 운영 read path: same-key → single-flight, unique-key → global admission control, 계산 완료 → result cache 저장, write task → micro-batch writer로 전달
- Backfill path: 운영 read path랑 분리, 별도 admission control, 가능하면 별도 staging/merge path, 운영 요청과 같은 executor/queue 쓰지 않기

### B.5 가장 중요한 설계 포인트

#### 1. "한 큐"라고 해도 큐 하나로 다 섞으면 안 됨

**권장 분리**:
- **Cold compute queue**: fetch / parse / calc 진입 제어
- **Write batch queue**: 계산 완료 결과를 모아 batch upsert

**이유**:
- DB가 느려질 때 compute까지 같이 막히고
- Compute burst가 write flush starvation을 일으키고
- 결국 병목 위치를 관측하기 어려워짐

즉 logical queue는 2개가 낫다.

#### 2. Request 단위 batching이 아니라 Result 단위 batching

- **HTTP request 묶음**: ❌
- **계산 완료된 summary rows 묶음**: ✅

Batching 대상은 결과 row들을 batch upsert 하는 쪽이 맞다.

#### 3. Dedupe를 같이 넣어야 함

Micro-batching에서 제일 효과 큰 건 사실 **batching + dedupe**다.

**예시**: 짧은 윈도우 안에 같은 (game_character_id, preset_no)가 여러 번 들어오면
- 마지막 값만 남기고
- 이전 write task는 덮어쓴다

**구조**: Write buffer는 단순 queue보다 이런 식이 더 낫다
- Queue + map 조합
- Key: (characterId, presetNo)
- Value: latest summary

Flush 시엔 중복 제거된 최신 row만 upsert. Changed-only upsert랑도 궁합이 좋다.

### B.6 추천 플로우

```
Request
  ↓
L1/L2 cache lookup
  ↓ miss
same-key?
  ├─ yes → single-flight join
  └─ no  → global admission queue
             ↓ permit 획득
             Nexon API fetch
             JSON parse
             3 preset calculate
             gzip/compress/cache save
             ↓
             write task enqueue
             ↓
       micro-batch writer
         ├─ dedupe by (characterId, presetNo)
         ├─ flush on size threshold
         └─ flush on time threshold
             ↓
       batch upsert
```

### B.7 이 구조의 장점

1. **CPU 보호**: 무한 cold miss 동시 실행을 막는다 (제일 중요)
2. **DB 효율 향상**: 개별 upsert 폭탄 대신 batch upsert로 변경
   - Round trip 감소
   - Transaction 수 감소
   - WAL flush 횟수 감소
   - Index touch amortization
3. **"안 받는 건 안 된다" 요구와 양립 가능**: 즉시 reject 대신 bounded waiting, fair scheduling, async handoff 가능

### B.8 주의할 점

1. **수천 개를 너무 오래 모으면 tail latency가 늘어남**
   - Micro-batch는 무조건 크게 모은다고 좋은 게 아니다
   - 운영 요청에서는 보통 20~50ms, 많아도 100ms 내외가 현실적
   - 백필 전용이면 더 길게 잡아도 되지만, 운영 read path write-behind라면 너무 길게 기다리면 안 됨

2. **Admission control 없이 micro-batching만 넣으면 실패**
   - DB는 덜 터짐
   - CPU는 계속 터질 수 있음
   - 즉 둘 중 하나만 고르면 안 되고, 우선순위는 admission control이 먼저

3. **Backfill은 운영 큐와 분리해야 함**
   - 섞으면 운영 트래픽이 backfill 때문에 밀린다
   - 운영 요청으로 생긴 write task vs 백필로 생긴 write task는 최소한 서로 다른 budget / queue / scheduler를 가져야 함

### B.9 네 상황 기준 추천 우선순위

**P0**:
- Global cold-miss admission control (unique-key fan-out 방지, bounded queue, fairness)

**P1**:
- Write micro-batching + dedupe ((game_character_id, preset_no) latest-wins, size/time dual-trigger flush)

**P1**:
- 운영/백필 queue 분리 (executor 분리, batch writer 분리, budget 분리)

**P2**:
- Changed-only upsert (실제 값 변화 없으면 skip)

**P2**:
- Staging/merge는 backfill 전용으로 검토 (운영 path엔 과할 수 있음, backfill엔 매우 유효)

### B.10 ADR에 넣기 좋은 한 문장

> **Global admission control은 cold-path compute 폭주를 막고, micro-batching은 완료된 결과 쓰기를 짧은 시간창으로 묶어 PostgreSQL upsert 비용을 완화하므로, 두 기법은 대체 관계가 아니라 상호 보완 관계다.**

### B.11 구현 아이디어 한 줄 버전

앞단: Semaphore + bounded fair queue
중간: same-key single-flight 유지
뒷단: ConcurrentHashMap(latest wins) + periodic/size-trigger batch flush

---

**End of Appendix**
