# ADR-028: 300k 캐릭터 벌크 로딩 (Bulk Loading 300k Characters)

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 수락됨 (Accepted) |
| 결정일 | 2026-03-22 |
| 결정자 | probabilistic-valuation-engine Team |
| 검토자 | Architecture Review Board |
| 관련 이슈 | #611 |
| 선행 ADR | ADR-021 Adaptive Micro-Batching, ADR-025 Observability Metrics |

---

## 1. 배경 (Context)

### 문제 상황

300,000개의 캐릭터 데이터를 CSV에서 로드하여 캐시에 워밍업해야 합니다. 단순 반복 호출은 다음 문제를 야기합니다:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Bulk Loading Challenge                        │
│  ┌────────┐  ┌────────┐  ┌────────┐       ┌──────────────────┐ │
│  │ Char 1 │  │ Char 2 │  │ Char N │  ──>  │   External API   │ │
│  │        │  │        │  │(300k)  │       │   Rate Limits    │ │
│  └────────┘  └────────┘  └────────┘       │                  │ │
│       │           │           │           │  ⚠️ 429 Errors   │ │
│       ▼           ▼           ▼           └──────────────────┘ │
│  ┌─────────────────────────────────┐                             │
│  │   Without throttling:           │                             │
│  │   - API Rate Limits (429)       │                             │
│  │   - No resume capability        │                             │
│  │   - No failure tracking         │                             │
│  └─────────────────────────────────┘                             │
└─────────────────────────────────────────────────────────────────┘
```

### 요구사항

| 항목 | 요구사항 |
|------|----------|
| **처리량** | 300,000개 캐릭터 처리 |
| **동시성** | 100개 Semaphore permits |
| **복구** | Checkpoint 기반 재개 기능 |
| **진행률** | 실시간 진행률 및 ETA |
| **실패 처리** | 실패한 캐릭터 추적 및 재시도 |
| **적응형** | API 응답에 따른 동적 속도 조절 |

---

## 2. 결정 (Decision)

**Semaphore 기반 동시성 제어와 적응형 쓰로틀링, 체크포인트 기반 재개 기능을 갖춘 벌크 로딩 시스템을 구현합니다.**

### 핵심 설계 원칙

1. **Semaphore 기반 동시성 제어**
   - 100개 permits로 동시 실행 제한
   - `CompletableFuture.runAsync()`로 비동기 처리

2. **적응형 쓰로틀링 (Adaptive Throttling)**
   - 성공 연속 시: 배치 크기 증가, 지연 감소
   - 429 응답 시: 배치 크기 감소, 지연 증가 (지수 백오프)
   - 타임아웃 시: 중간 백오프

3. **체크포인트 기반 재개**
   - 500개 처리마다 체크포인트 저장
   - JSON 형식으로 완료된 IGN 목록 유지
   - 장애 시 재개 가능

4. **진행률 추적**
   - 100개 처리마다 로그 출력
   - ETA 계산 및 Micrometer 메트릭

5. **실패 추적**
   - 실패한 IGN을 CSV로 저장
   - 에러 타입별 분류 (RATE_LIMIT, TIMEOUT, NOT_FOUND)

---

## 3. 대안 (Alternatives)

### A. 단순 순차 처리

**장점:**
- 구현 단순

**단점:**
- 처리 시간 과다 (300k × 100ms = 8.3시간)
- API 속도 제한 고려 없음

**평가:** ❌ 비현실적

### B. 고정 속도 병렬 처리

**장점:**
- 일정한 처리 속도

**단점:**
- 429 응답 시 대응 불가
- 과도한 쓰로틀링으로 비효율

**평가:** ⚠️ 적응성 부족

### C. 적응형 벌크 로딩 (선택됨)

**장점:**
- API 상태에 따른 동적 조절
- Checkpoint로 재개 가능
- 실패 추적 및 재시도

**단점:**
- 구현 복잡도

**평가:** ✅ 최적 솔루션

---

## 4. 기술적 구현 (Implementation)

### 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         BulkLoaderService                                    │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                        Entry Points                                   │  │
│  │  loadAll(csvPath, force)  |  resume()  |  retryFailed()             │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                      │                                      │
│                                      ▼                                      │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                    LockStrategy (Distributed Lock)                    │  │
│  │                    Lock Key: "bulk:load:lock"                         │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                      │                                      │
│                                      ▼                                      │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                    processBatch() - Semaphore(100)                    │  │
│  │                                                                       │  │
│  │   forEach IGN:                                                        │  │
│  │     CompletableFuture.runAsync {                                      │  │
│  │       semaphore.acquire()                                             │  │
│  │       executor.executeWithFinally(                                    │  │
│  │         task = { processCharacter() },                                │  │
│  │         finallyBlock = { semaphore.release() }                        │  │
│  │       )                                                               │  │
│  │     }                                                                  │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                      │                                      │
│                                      ▼                                      │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                    processCharacter()                                 │  │
│  │                                                                       │  │
│  │   1. Check backpressure (writeBackBuffer.pendingCount)               │  │
│  │   2. Skip if already completed                                       │  │
│  │   3. executor.executeOrCatch {                                       │  │
│  │        cacheWarmupPort.warmup(ign, force)                            │  │
│  │      } recovery { e -> recordFailure(ign, e) }                       │  │
│  │   4. Progress logging (every 100)                                    │  │
│  │   5. Checkpoint save (every 500)                                     │  │
│  │   6. Adaptive throttle delay                                         │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 핵심 컴포넌트

#### 1. BulkLoadProperties (설정)

```kotlin
@ConfigurationProperties(prefix = "bulk")
data class BulkLoadProperties(
    val csvPath: String = "./characters.csv",
    val batch: BatchConfig = BatchConfig(),
    val delay: DelayConfig = DelayConfig(),
    val semaphore: SemaphoreConfig = SemaphoreConfig(),
) {
    data class BatchConfig(
        val initialSize: Int = 100,
        val minSize: Int = 10,
        val maxSize: Int = 200,
    )

    data class DelayConfig(
        val initialMs: Long = 100,
        val minMs: Long = 50,
        val maxMs: Long = 5000,
    )

    data class SemaphoreConfig(
        val permits: Int = 100,
    )
}
```

#### 2. CheckpointManager (재개 기능)

```kotlin
@Component
class CheckpointManager(
    @Value("\${bulk.checkpoint.path:./checkpoint.json}")
    private val checkpointPath: String,
    private val executor: LogicExecutor,
) {
    data class Checkpoint(
        val completedIgnSet: Set<String>,
        val lastProcessedIndex: Int,
        val totalCharacters: Int,
        val timestamp: Instant,
    )

    fun save(completedIgnSet: Set<String>, lastIndex: Int, total: Int)
    fun load(): Checkpoint?
    fun clear()
}
```

#### 3. ProgressLogger (진행률 추적)

```kotlin
@Component
class ProgressLogger(private val meterRegistry: MeterRegistry) {
    data class Progress(
        val loaded: Int,
        val total: Int,
        val errors: Int,
        val ratePerSecond: Double,
        val etaMinutes: Int,
    )

    fun logProgress(progress: Progress)
    fun calculateRate(loaded: Int, startTime: Instant): Double
    fun calculateEta(loaded: Int, total: Int, startTime: Instant): Int
}
```

#### 4. FailedCharactersTracker (실패 추적)

```kotlin
@Component
class FailedCharactersTracker(
    @Value("\${bulk.failed.path:./failed.csv}")
    private val failedPath: String,
    private val executor: LogicExecutor,
) {
    data class FailedEntry(
        val userIgn: String,
        val errorType: String,
        val timestamp: LocalDateTime,
        val retryCount: Int,
    )

    fun record(entry: FailedEntry)
    fun save()
    fun load(): List<FailedEntry>
    fun clear()
}
```

#### 5. AdaptiveThrottler (적응형 쓰로틀링)

```kotlin
@Component
class AdaptiveThrottler {
    sealed class ThrottleDecision(
        val batchSize: Int,
        val delayMs: Long,
        val shouldPause: Boolean,
    ) {
        class Proceed(batchSize: Int, delayMs: Long) : ThrottleDecision(batchSize, delayMs, false)
        class Pause(batchSize: Int, delayMs: Long) : ThrottleDecision(batchSize, delayMs, true)
    }

    fun onSuccess(): ThrottleDecision
    fun on429(): ThrottleDecision
    fun onTimeout(): ThrottleDecision
}
```

### YAML 설정

```yaml
# application.yml
bulk:
  csv-path: ./characters.csv
  batch:
    initial-size: 100
    min-size: 10
    max-size: 200
  delay:
    initial-ms: 100
    min-ms: 50
    max-ms: 5000
  semaphore:
    permits: 100
  checkpoint:
    path: ./checkpoint.json
  failed:
    path: ./failed.csv
```

---

## 5. REST API

```kotlin
@RestController
@RequestMapping("/api/admin/bulk")
class BulkLoadController(
    private val bulkLoaderService: BulkLoaderService,
) {
    @PostMapping("/load")
    fun startLoad(@RequestParam force: Boolean): ResponseEntity<LoadResult>

    @PostMapping("/resume")
    fun resume(): ResponseEntity<LoadResult>

    @PostMapping("/retry-failed")
    fun retryFailed(): ResponseEntity<LoadResult>

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<BulkLoadStatus>

    @PostMapping("/stop")
    fun stop(): ResponseEntity<Map<String, String>>
}
```

---

## 6. CLAUDE.md 준수

### Zero Try-Catch Policy 준수

| 패턴 | 사용 메서드 | 위치 |
|------|-------------|------|
| Resource Management | `executor.executeWithFinally()` | `BulkLoaderService:264-273` |
| Exception Recovery | `executor.executeOrCatch()` | `BulkLoaderService:348-363` |
| Void Operations | `executor.executeVoid()` | `CheckpointManager`, `FailedCharactersTracker` |
| Default Values | `executor.executeOrDefault()` | `CheckpointManager`, `FailedCharactersTracker` |

---

## 7. 트레이드오프 (Trade-offs)

### 장점

| 항목 | 설명 |
|------|------|
| **대규모 처리** | 300k 캐릭터 처리 가능 |
| **재개 기능** | Checkpoint로 장애 시 재개 |
| **적응형** | API 상태에 따른 동적 조절 |
| **가시성** | 실시간 진행률 및 ETA |

### 단점 및 완화 방안

| 항목 | 완화 방안 |
|------|----------|
| **메모리 사용** | ConcurrentHashMap으로 제한적 사용 |
| **Checkpoint 오버헤드** | 500개마다 저장으로 최소화 |
| **복잡도** | 명확한 컴포넌트 분리 |

---

## 8. 성능 목표

| 지표 | 목표 |
|------|------|
| **처리량** | 300,000 캐릭터 |
| **동시성** | 100 Semaphore permits |
| **처리 속도** | ~100/sec (적응형) |
| **예상 소요 시간** | ~50분 (이상적) ~2시간 (429 포함) |

---

## 9. 모니터링 & 검증

### 메트릭

| 메트릭 | 용도 |
|--------|------|
| `bulk_progress_loaded_count` | 로드된 캐릭터 수 |
| `bulk_progress_total_count` | 전체 캐릭터 수 |
| `bulk_progress_error_count` | 에러 수 |
| `bulk_progress_rate_per_second` | 처리 속도 |
| `bulk_progress_eta_minutes` | 예상 남은 시간 |

### 로그 포맷

```
[BulkLoaderService] Loaded 15000/300000 (5.0%) | ETA: 45min | Errors: 12 | Rate: 100/sec
```

---

## 10. 참고 자료

- [ADR-021 Adaptive Micro-Batching](021-adaptive-micro-batching.md)
- [ADR-025 Observability Metrics Rules](ADR-025-observability-metrics-rules.md)
- [LogicExecutor Interface](../module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/LogicExecutor.kt)

---

## 11. 변경 이력

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-03-22 | ADR 초안 작성 | probabilistic-valuation-engine Team |
