# ADR-086: Performance Baseline Analysis & Bottleneck Determination

**Date**: 2026-03-24
**Status**: Accepted
**Context**: Issue #611 (Bulk Load), Performance Baseline Establishment
**Persisted Dataset**: 158,428 rows in `equipment_expectation_summary`

---

## Executive Summary

### The Bottleneck in One Sentence

> **"이 시스템은 캐시 미스 시 외부 API fetch + 대형 JSON 파싱 + 3 프리셋 확률 계산 + gzip 압축이 결합된 cold-path를 가지며, 백필 시에는 PostgreSQL upsert/write amplification이 부가적인 병목으로 작용한다."**

### Core Determination

| Bottleneck Type | Primary Concern | Secondary Concern |
|-----------------|------------------|-------------------|
| **Operational Read Path** | Cold-path admission control (unique-key fan-out) | CPU pipeline (parse/calc/compress) |
| **Backfill Write Path** | PostgreSQL upsert write amplification | Index maintenance, WAL, checkpoint |

**Conclusion**: 운영 병목과 백필 병목을 **분리해서 바라봐야 한다**.

---

## 1. Context & Background

### 1.1 Completed Fixes (Pre-Analysis)

| Issue | Fix | Status |
|-------|-----|--------|
| Nexon API fetch timeout | 10s → 28s, Resilience4j 정렬 | ✅ Resolved |
| Distributed lock failure | waitTime 0 → 3 seconds | ✅ Resolved |
| SQL dialect mismatch | MySQL → PostgreSQL ON CONFLICT | ✅ Resolved |
| Persisted row count | 158,428 rows, 0 duplicates | ✅ Verified |

### 1.2 Original Concerns

1. **CPU 700% observed** (8-core full utilization)
2. **Backfill throughput degradation** as dataset grows
3. **"Must accept all requests"** requirement (no simple 429 rejection)
4. **Fan-out explosion scenario**: 1000 users × 1000 different characters

---

## 2. Verified Findings

### 2.1 CPU Pipeline Components (NOT YET PROFILED)

> **⚠️ Important**: The following are **inferences**, not measured values. Actual profiling (async-profiler/JFR) required.

| Operation | Estimated CPU Cost | Verification Status |
|-----------|-------------------|---------------------|
| JSON Parsing (200~300KB) | 20-25% | ⚠️ Inference (needs profiling) |
| Probability Convolution DP | 15-20% | ⚠️ Inference (needs profiling) |
| 3 Preset Calculation | 15-20% | ✅ Already parallelized |
| Gzip Compression | 8-12% | ⚠️ Inference (needs profiling) |
| PostgreSQL upsert | 5-10% | ⚠️ Inference (needs profiling) |

**Confirmed Fact**: BigDecimal already migrated to Double + Kahan Summation (2026-03-23).

### 2.2 Cache Architecture (Verified)

| Layer | Technology | Status |
|-------|------------|--------|
| L1 | Caffeine (local) | ✅ Active |
| L2 | PostgreSQL (UNLOGGED table) | ✅ Active |
| Redis | **Removed** (ADR-022, Issue #589) | ✅ Confirmed |
| Rate Limiting | **Disabled** (`ratelimit.enabled: false`) | ⚠️ Not protecting fan-out |

### 2.3 Single-Flight Limitations (Verified)

**What it protects**: Same-key stampede (1000 users, 1 character)

**What it does NOT protect**: Unique-key fan-out (1000 users, 1000 different characters)

**Evidence** (`TieredCache.kt:196-209`):
- Lock key: `"cache:sf:{cacheName}:{ocid}"`
- Each unique OCID gets its own lock
- No global admission control

---

## 3. Bottleneck Determination

### 3.1 Operational Read Path Bottleneck

**Primary Concern**: **Cold-path admission control**

**Scenario**: 1000 users request 1000 different characters simultaneously

```
Result (without admission control):
- 1000 concurrent Nexon API fetches
- 1000 × (200~300KB JSON parse)
- 1000 × (3 preset calculation)
- 1000 × (gzip compression)
- 1000 × (DB write-behind buffer offer)
= CPU saturation + connection pool exhaustion
```

**Why single-flight is insufficient**:
- Single-flight coalesces requests for the **same** key only
- With 1000 unique OCIDs, 1000 distributed locks are acquired independently
- No protection against this type of fan-out

**Required Mitigation**:
1. **Global in-flight budget** (not per-key)
2. **Bounded queue + fair waiting**
3. **Separation of cancellation vs timeout**

### 3.2 Backfill Write Path Bottleneck

**Primary Concern**: **PostgreSQL upsert write amplification**

**Observed**: Throughput degradation as dataset grows (15만 → 30만 rows)

**Root Causes**:
1. **Unique index maintenance**: `uk_character_preset (game_character_id, preset_no)`
2. **WAL growth**: Large WAL → longer checkpoint duration
3. **Autovacuum overhead**: Upsert creates dead tuples (UPDATE = delete + insert)
4. **Using operational path**: Designed for read-heavy workloads, not bulk insert

**Evidence**: 158,428 rows successfully persisted, but backfill rate noticeably slowed toward the end.

---

## 4. Dataset Sufficiency Assessment

### 4.1 Question

> "15만~16만 persisted dataset 상태로 현실 운영형 부하테스트 해도 충분한가?"

### 4.2 Answer: **Conditional Yes**

| Purpose | Sufficient? | Reason |
|----------|-------------|--------|
| **Operational read-heavy feasibility** | ✅ Yes | Hot set / non-hot set separation achievable |
| **Mixed distribution testing** | ✅ Yes | Cold/warm cache hit rates measurable |
| **Cache structure validation** | ✅ Yes | L1/L2/invalidation paths testable |
| **Multi-instance invalidation** | ✅ Yes | LISTEN/NOTIFY behavior verifiable |
| **Write path growth limits** | ❌ No | Cannot extrapolate 30만~100만 behavior |
| **Index degradation at scale** | ❌ No | Unique index cost curve unknown |
| **WAL/checkpoint impact** | ❌ No | Large dataset effects not tested |
| **Long-tail extreme cases** | ❌ No | Zipf tail behavior not validated |

### 4.3 Refined Statement

> **"15만 rows는 운영 read-path baseline 검증에는 충분하나, write-path 성장 한계 검증에는 불충분하다."**

---

## 5. Redis/Kafka Assessment

### 5.1 Redis

**Verdict**: **Not immediately necessary**

**Rationale**:
| Use Case | Redis Benefit | Current Alternative |
|----------|---------------|---------------------|
| Hot key optimization | High | ✅ L1 Caffeine (already effective) |
| Shared L2 cache | Medium | ✅ PostgreSQL L2 (functional) |
| Multi-instance invalidation | High | ✅ LISTEN/NOTIFY (available) |
| **Unique-key fan-out protection** | **Low** | ❌ Admission control needed instead |

**Key Insight**: Redis solves hot key reuse, but **not** unique-key cold miss explosion. The core concern is admission control, not cache technology.

### 5.2 Kafka

**Verdict**: **Over-engineering for current needs**

**Rationale**:
| Use Case | Kafka Benefit | Current Alternative |
|----------|---------------|---------------------|
| Burst absorption | High | ✅ Write-behind buffer (Disruptor-style) |
| Async decoupling | High | ✅ Batch scheduler (5s flush) |
| Backfill queue | High | ⚠️ Manual/in-process (needs improvement) |
| **Backfill path optimization** | **Low** | ❌ Staging table + merge more appropriate |

**Key Insight**: Kafka makes backfill "more elegant" but does not solve the primary bottleneck (upsert write amplification). Staging table + merge is more cost-effective.

---

## 6. Optimization Priority (Evidence-Based)

### 6.1 P0: Global Cold-Path Admission Control

**Why First**:
- Directly addresses the "must accept all requests" requirement
- Prevents CPU saturation from unique-key fan-out
- Enables graceful degradation under burst

**Approach**:
1. Global in-flight budget (separate from per-key lock)
2. Bounded queue with fair waiting
3. Request prioritization (force refresh vs normal)
4. Separation of cancellation vs timeout

**Expected Impact**: Prevents system overload during cold cache periods.

### 6.2 P0: CPU Pipeline Profiling

**Why Second**:
- Current CPU% breakdown is inference, not measurement
- Need to identify actual hotspots before micro-optimization

**Required Tools**:
- async-profiler or JFR
- Flamegraph generation
- Allocation hotspot analysis
- GC pause measurement

**Candidates to Profile**:
- `EquipmentStreamingParser` (JSON parsing)
- `ProbabilityConvolver` / `FlameDpCalculator` (DP algorithm)
- `GzipUtils.compress` (compression)
- `PresetCalculationHelper.calculatePreset`

**Expected Impact**: Data-driven optimization decisions.

### 6.3 P1: Backfill-Specific Write Path

**Why Third**:
- Operational path optimized for read-heavy workloads
- Bulk insert has different characteristics
- Current upsert path creates unnecessary write amplification

**Approach**:
1. Staging table (no indexes) for raw insert
2. `COPY` or bulk insert (not upsert)
3. Merge/upsert as post-processing step
4. Optional index rebuild timing

**Expected Impact**: 3-10x backfill speed improvement.

### 6.4 P1: Changed-Only Upsert

**Why Fourth**:
- Reduces write amplification
- Meaningful for characters with minimal stat changes

**Approach**: Dirty tracking + conditional upsert only when values change significantly.

**Expected Impact**: 30-50% write reduction in steady-state operations.

### 6.5 P2: Gzip Strategy Review

**Why Fifth**:
- Compression is necessary (payload size, cache storage)
- But level/timing may be tunable

**Review Items**:
- Gzip level (default vs tuned)
- Pre-compressed representation storage
- Avoid redundant decompress/recompress cycles

---

## 7. Confirmed vs. Unverified Claims

### 7.1 Confirmed (✅)

| Claim | Evidence |
|-------|----------|
| BigDecimal → Double migrated | Code: `PresetCalculationHelper.java:73` |
| 3 preset already parallelized | Code: `EquipmentExpectationServiceV4.java:257-277` |
| Redis removed (ADR-022) | Code: Issue #589 references |
| Rate limiting disabled | Config: `ratelimit.enabled: false` |
| 158,428 rows persisted | DB count, 0 duplicates verified |
| Single-flight per-key only | Code: `TieredCache.kt:196-209` |

### 7.2 Unverified (⚠️)

| Claim | Required Verification |
|-------|---------------------|
| Parse vs calc vs gzip CPU breakdown | async-profiler/JFR measurement |
| Operational QPS limit root cause | Load test with admission control |
| 30만+ row degradation rate | Staging table + merge comparison |
| Multi-instance invalidation cost | LISTEN/NOTIFY benchmark |

---

## 8. Decision Record

### 8.1 What We Decided

1. **Do NOT add Redis yet** - PostgreSQL L2 + admission control sufficient
2. **Do NOT add Kafka yet** - Staging table + merge more appropriate for backfill
3. **DO implement global admission control** - Addresses unique-key fan-out
4. **DO profile CPU pipeline** - Replace inference with measurement
5. **DO separate backfill write path** - Different workload characteristics

### 8.2 What We Deferred

1. **DP parallelization** - Thread overhead risk, needs profiling first
2. **Gzip level tuning** - Lower priority vs admission control
3. **Additional dataset growth** - Complete 15만 baseline first

### 8.3 Success Criteria

| Criterion | Current State | Target |
|-----------|---------------|--------|
| Operational baseline | 15만 rows | ✅ Sufficient for read-path |
| Admission control | ❌ Not implemented | ✅ Global in-flight limit |
| CPU profiling | ❌ Inferences only | ✅ Measured hotspots |
| Backfill path | Mixed with operational | ✅ Separated pipeline |
| Changed-only upsert | ❌ Always upsert | ✅ Conditional writes |

---

## 9. References

| Resource | Link/Location |
|----------|---------------|
| Original Analysis | `docs/analysis/BOTTLENECK_ANALYSIS_20260324.md` |
| 3 Preset Parallelization | `EquipmentExpectationServiceV4.java:257-277` |
| Kahan Summation | `PresetCalculationHelper.java:73-92` |
| Batch Upsert | `EquipmentExpectationSummaryBatchRepository.kt:64-77` |
| PostgreSQL Migration (V102) | `V102__load_test_index_optimization.sql` |
| Cache Configuration | `application.yml:292-330` |

---

## 10. Commit Message Template

```
perf: establish performance baseline & identify bottlenecks

Achievements:
- Nexon API fetch timeout aligned with Resilience4j (10s → 28s)
- Distributed lock wait policy adjusted (0 → 3s)
- MySQL → PostgreSQL upsert dialect corrected (ON CONFLICT)
- 158,428 rows persisted, 0 duplicates verified

Bottlenecks Identified:
- Primary: Cold-path admission control (unique-key fan-out)
- Secondary: CPU pipeline (parse/calc/compress)
- Tertiary: Backfill upsert write amplification

Decisions:
- Redis/Kafka deferred (not immediate necessity)
- Profiling required for CPU hotspots (currently inference)
- Backfill path separation prioritized over operational optimization

Dataset Assessment:
- 15만 rows: Sufficient for operational read-path baseline
- Insufficient for write-path growth limit verification

Next: ADR-087 implementation (admission control + profiling)

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
```

---

**End of ADR**
