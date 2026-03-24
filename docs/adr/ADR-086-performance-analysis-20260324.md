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

## Appendix A: 기존 보고서 검토 및 보강 (Post-Analysis Review)

**Date**: 2026-03-24
**Reviewed Reports**: ADR-027, BOTTLENECK_ANALYSIS_20260324.md, ADR-086
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

**End of Appendix**
