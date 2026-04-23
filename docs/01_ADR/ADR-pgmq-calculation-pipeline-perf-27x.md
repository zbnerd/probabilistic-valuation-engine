# ADR: PGMQ 계산 파이프라인 27배 성능 최적화 여정

## 상태: 완료 (2026-04-23)

## 메트릭 요약

| 지표 | Before (04-21) | After (04-23) | 변화 |
|------|----------------|---------------|------|
| Drain 속도 | 3.3 tasks/sec | 90 tasks/sec | **27x** |
| CalculateOnly avg | 25,466ms | 864ms | **29x** |
| 요청 throughput | 99.7 req/s (503 98%) | 336 req/s (에러 0) | **3.4x** |
| Cache:Get avg | 1,511ms | N/A (L2 제거) | **제거** |
| 503 에러율 | 98.4% | 0% | **제거** |

---

## 워크로드 성격

단순 계산 작업이 아니다. 90 TPS는 다음 **혼합 워크로드**를 처리한 수치다:

```
200~300KB JSON (Nexon Open API)
  → 역직렬화 (Jackson)
    → 스트리밍 파싱 (3개 프리셋 × ~20 장비 아이템)
      → 확률 합성곱 계산 (CPU-bound)
        → Bulk JDBC batch write (DB I/O)
```

네트워크 I/O + 역직렬화 + 파싱 + CPU 계산 + DB write가 **단일 파이프라인에서 혼합**되어 있고,
이 전체를 읽기·쓰기 동시성 병목을 단계적 제거하면서 3.3 → 90 req/s, 계산 지연 25s → 0.86s로 도달했다.

"혼합 워크로드"라는 점이 중요하다. 순수 CPU 벤치마크나 순수 I/O 벤치마크가 아니다.
각 단계의 병목 성격이 서로 다르기 때문에, VT pinning(CPU) → L2 캐시(DB I/O) → 파싱(역직렬화) → 코루틴(오케스트레이션)처럼
**최적화 전략 자체가 단계마다 달라야 했다.**

---

## 핵심 관점: 겹겹이 쌓인 병목의 순차 제거

25초 → 0.86초는 단일 최적화의 결과가 아니다. **각 병목이 제거될 때마다 다음 병목이 드러나는 구조**였고, 어느 하나만 했으면 저 수치가 나오지 않았다.

```
25,466ms → 864ms 로 도달한 7개 겹:

1. VT pinning 제거        → 18/sec (가장 큰 단일 임팩트)
2. Bulk JDBC upsert       → DB write 병목 제거
3. Semaphore 제거         → 오케스트레이션 오버헤드 제거
4. 3프리셋 → 1프리셋      → 계산량 자체를 줄임
5. L2 캐시 DB 제거        → 오히려 병목이던 걸 제거
6. compute key dedup      → 중복 계산 90% 제거
7. 코루틴 chunk 병렬      → 마지막 한 겹
```

**병목 드러남의 흐름:**

```
VT pinning (Phase 2.3)
    ↓ 제거 → 다음 병목 드러남
Bulk JPA save (Phase 2.2)
    ↓ 제거 → 다음 병목 드러남
Semaphore 오버헤드 (Phase 2.1)
    ↓ 제거 → 다음 병목 드러남
3프리셋 전체 파싱 (Phase 3.3)
    ↓ 제거 → 다음 병목 드러남
L2 캐시 DB 왕복 (Phase 4.1)
    ↓ 제거 → 다음 병목 드러남
중복 계산 90% (Phase 3.1)
    ↓ 제거 → 마지막 병목 드러남
순차 chunk 처리 (Phase 4.2)
    ↓ 코루틴 병렬로 제거 → 90/sec 도달
```

이것이 진짜 엔지니어링 서사다. 각 단계에서 "이제 충분히 빠른가?"가 아니라 "지금 무엇이 가장 느린가?"를 물었고, 그 답이 바뀔 때마다 다음 최적화가 정당화되었다.

---

## 컨텍스트

### 초기 상태 (2026-04-19)

10K 부하 테스트에서 캐릭터 기대값 계산 파이프라인이 마비 상태:

```
PGMQ poll (Semaphore=40)
  → Virtual Thread × N (unbounded)
    → Per-message: 3 preset fan-out
      → Per-preset: ~20 item fan-out
        → CompletableFuture.supplyAsync × 60
          → ThreadPoolTaskExecutor(32/32/5000)
            → Semaphore(64)
              → BlockingSubmitExecutor
                → compute
  → PipelineBuffer(500)
    → drain → batchWrite
```

**SLOW_TASK_ANALYSIS.md 기록 (04-21):**

```
PgmqWorker:CalculateOnly  avg=25,466ms  count=93,236  (전체 slow task의 69%)
```

P50=28.5초, P99=30.6초. 거의 모든 태스크가 Bulkhead timeout ceiling(30초)에 도달.

### 근본 원인

1. **Virtual Thread carrier pinning**: CPU-bound 확률 합성곱 연산이 carrier thread를 블로킹. 모든 VT가 동일 carrier에서 직렬화
2. **Burst amplification**: 40 메시지 × 3 프리셋 × 20 아이템 = 2,400 CompletableFuture 동시 submit
3. **Executor 정책에 correctness 의존**: supplyAsync reject 시 thenCombine 체인 전체 실패
4. **Semaphore(64) 무의미**: ThreadPool max=32, Semaphore permits=64. 항상 즉시 acquire
5. **Cache:Get L2 병목**: avg 1,511ms, 총 4,397초 소요. 네트워크 왕복 + 직렬화/역직렬화

---

## 의사결정 타임라인

### Phase 1: 아키텍처 구축 (04-19 ~ 04-20)

#### 1.1 Two-Phase Batch UPSERT

**PR**: pipeline 아키텍처 (#729, #730)
**파일**: `PgmqWorker.kt`, `AbstractExpectationCalcWorker.kt`

**의사결정**: Per-row JPA save → two-phase batch (Phase 1: calculate, Phase 2: batchWrite)

```
Before: save() per row → 30-120 DB round trips
After:  batch SELECT + batch UPDATE + batch INSERT → 3 queries
```

**결과**: DB 왕복 97% 감소. 하지만 VT pinning이 여전히 근본 병목이라 전체 throughput 개선 제한적.

#### 1.2 아이템 병렬화 시도 (실패 → revert)

**커밋**: `d0352eb7 feat(calc): parallelize equipment calculation within presets`
**Revert**: `d399c61e perf(executor): revert itemCalculationExecutor to ThreadPool`

**시도**: 프리셋 내 장비 계산을 CompletableFuture로 병렬화

**실패 원인**:
- 3 presets × 10 items = 30 tasks/request × 100 requests = 3,000 tasks
- Thread pool saturation → RPS 118 → 88로 회귀
- CPU 경합으로 개별 task latency 증가

**학습**: **Preset-level 병렬화로 충분. Item-level은 과병렬화.**

```java
// Reverted: item-level parallelization
CompletableFuture<?>[] futures = inputs.stream()
    .map(input -> CompletableFuture.supplyAsync(() -> calculate(input), executor))
    .toArray(CompletableFuture[]::new);
CompletableFuture.allOf(futures).join();
```

#### 1.3 Virtual Thread item executor (실패 → revert)

**커밋**: `cb8f54b7 feat(executor): separate itemCalculationExecutor with Virtual Thread`
**Revert**: `d399c61e` (동일 커밋에서 revert)

**시도**: 아이템 계산용 executor를 VT로 전환

**실패 원인**: ItemCalculationExecutorConfig에 기록됨

```
// ItemCalculationExecutorConfig.kt 주석
// VT rejected for CPU-bound: 3.5x latency regression confirmed
// Reason: ProbabilityConvolver CPU work pins carrier threads
```

**학습**: **CPU-bound 작업에 VT 금지. Platform Thread 고정.**

### Phase 2: 병목 제거 (04-22)

#### 2.1 Semaphore(64) 제거 (#733)

**커밋**: `2773210b refactor: remove Semaphore(64) from item calculation`

**의사결정**: ThreadPool max=32인데 Semaphore permits=64. 항상 즉시 acquire되어 gate로서 무의미.

**결과**: Semaphore 대기 시간(수백ms) 제거. 하지만 VT pinning이 근본 병목이라 전체 throughput 개선 제한적.

#### 2.2 Bulk JDBC Upsert (#734)

**커밋**: `499b9bad refactor: bulk JDBC upsert for view batch writes`
**버그픽스**: `d3f6aeaa fix: use java.sql.Timestamp instead of Instant for JDBC batch params`

**의사결정**: JPA `save()` per-row → JDBC batch upsert

```java
// Before: JPA per-row
for (ViewEntity entity : entities) {
    repository.save(entity);  // SELECT + INSERT/UPDATE per row
}

// After: Bulk JDBC
batchSelectByMessageIds(messages);
batchUpdate(existingEntities);
batchInsert(newEntities);
// → 3 queries regardless of batch size
```

**결과**: HikariCP pending=0, timeout=0. DB 병목 해소.

#### 2.3 VT → FixedThreadPool (#735) — 최대 임팩트

**커밋**: `0321e9d5 refactor: replace Virtual Thread with FixedThreadPool in PgmqWorker`

**의사결정**: `newVirtualThreadPerTaskExecutor()` → `newFixedThreadPool(workerPoolSize)`

**핵심 근거**: PgmqWorker는 CPU-bound 확률 계산을 수행. VT는 I/O 대기에 유리하지만, CPU-bound 작업에서 carrier thread pinning으로 역효과.

```kotlin
// Before: Virtual Thread (anti-pattern for CPU-bound)
private val workerPool: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

// After: Fixed Platform Thread
private val workerPool: ExecutorService by lazy {
    Executors.newFixedThreadPool(config.common.workerPoolSize) { runnable ->
        Thread(runnable, "$queueName-worker").apply { isDaemon = true }
    }
}
```

**결과**:

| 지표 | Before | After |
|------|--------|-------|
| 503 에러 | 9,846 (98.4%) | **0** |
| Throughput | 99.7 req/s | **511.9 req/s** |
| Avg response | 500ms | **97ms** |

**이것이 전체 여정에서 가장 큰 단일 개선.** VT carrier pinning이 모든 파이프라인을 막고 있었음.

#### 2.4 BlockingSubmitExecutor 제거 (#738)

**커밋**: `31d8025a refactor: remove BlockingSubmitExecutor wrapper`

**의사결정**: FixedThreadPool 전환 후 불필요해진 spin-wait retry wrapper 제거. -71 lines.

#### 2.5 PipelineBuffer Backpressure (#739)

**커밋**: `0837f2f0 refactor: align PipelineBuffer capacity with maxInflight`

**의사결정**: PipelineBuffer capacity를 독립 설정(500)에서 `maxInflight * 2`로 자동 정렬.

**결과**: Pipeline buffer full = 0건, 결과 드랍 = 0건.

### Phase 3: 처리량 증대 (04-22 ~ 04-23)

#### 3.1 Compute Key Dedup + Sequential Batch (#749)

**커밋**: `5dcfe95f` ~ `9f19cbb1` (6 commits)

**문제**: 동일 캐릭터가 여러 번 큐에 들어와 동일 계산 반복. computeBuffer hit rate 측정 결과 90%가 중복.

**의사결정 1 — BatchComputeBuffer**: Per-batch ConcurrentHashMap으로 계산 키 dedup

```java
// CubeComputeBuffer: batch 단위로 compute key dedup
public CubeCalculationResult getOrCompute(CubeComputeKey key, Supplier<CubeCalculationResult> supplier) {
    return buffer.computeIfAbsent(key, k -> supplier.get());
}
// 배치 종료 시 clear() → 다음 배치에서 fresh 계산
```

**의사결정 2 — AccumulationBuffer + Sequential Batch**: 메시지를 500ms 윈도우로 누적 후 chunk 단위 처리

```kotlin
// AccumulationBuffer: time-window batching
class AccumulationBuffer<T>(private val windowMs: Long) {
    private val buffer = mutableListOf<T>()
    private var lastFlush = 0L

    fun add(item: T) { buffer.add(item) }

    fun drain(): List<T> {
        val now = System.currentTimeMillis()
        return if (now - lastFlush >= windowMs && buffer.isNotEmpty()) {
            val batch = buffer.toList(); buffer.clear(); lastFlush = now; batch
        } else emptyList()
    }
}
```

```kotlin
// PgmqWorker: sequential batch processing
private fun processSequentialBatch(messages: List<PgmqMessage<T>>) {
    for (message in messages) {  // 순차 for-loop — CPU cache locality
        calculateOnly(message)
    }
    batchWrite(results)  // 한 번에 batch write
}
```

**결과**: Drain ~23/sec. Dedup hit rate 90%로 실제 계산량 1/10 감소.

#### 3.2 Batch L2 Cache (#750)

**커밋**: `4cf78ec6 feat(cache): add time-window batch L2 lookup`
**후속**: `36ce2960 perf(cache): add time-window batch L2 writes`

**의사결정**: 개별 L2 SELECT/UPSERT → time-window batch로 그룹화

**결과**: 개별 쿼리 감소. 하지만 L2 자체가 병목(Cache:Get avg 1,511ms)으로 이후 제거 결정.

#### 3.3 parseSinglePreset + presetNo (#753)

**커밋**: `c4d513bb perf(parser): add parseSinglePreset()`, `c936243a feat(api): add presetNo`
**DB**: `b760afeb feat(db): add preset_no column to character_valuation_views`

**문제**: 항상 3개 프리셋 전부 파싱(parseAllPresets)하지만 1개만 계산. 파싱에 600-950ms 소요.

**의사결정**: API → DB까지 presetNo 파라미터를 엔드투엔드 스레딩

```java
// Before: always parse all 3 presets
Map<Integer, List<CubeCalculationInput>> allPresets = parser.parseAllPresets(data);
List<CubeCalculationInput> inputs = allPresets.getOrDefault(presetNo, List.of());

// After: parse only target preset
List<CubeCalculationInput> inputs = parser.parseSinglePreset(data, presetNo);
```

```java
// parseSinglePreset: target field만 스캔
private List<CubeCalculationInput> doStreamParseSinglePreset(JsonParser parser, String targetField) {
    while (parser.nextToken() != null) {
        if (parser.currentToken() == JsonToken.FIELD_NAME && targetField.equals(parser.currentName())) {
            parser.nextToken();
            if (parser.currentToken() == JsonToken.START_ARRAY) {
                List<CubeCalculationInput> items = new ArrayList<>();
                parseItemArrayBounded(parser, items);
                return items;
            }
        }
    }
    return List.of();
}
```

**결과**: 파싱 시간 ~1/3 감소. Drain ~35/sec.

### Phase 4: L2 제거 + 코루틴 (04-23)

#### 4.1 Jackson KotlinModule 버그 수정 + L2 비활성화

**커밋**: `5076acc2 fix(jackson): register KotlinModule via @Primary ObjectMapper`

**문제**: `Jackson2ObjectMapperBuilderCustomizer`의 `builder.modules()`가 런타임에 KotlinModule을 등록하지 않음. 모든 Kotlin data class 역직렬화 실패 → PGMQ 워커 마비.

**의사결정 1 — Jackson 버전 정렬**: `2.17.0` → `2.19.2` (Spring Boot 3.5.4 BOM 일치)

**의사결정 2 — @Primary ObjectMapper 직접 생성**: Builder customizer 대신 ObjectMapper bean 직접 생성

```kotlin
// Before: unreliable customizer
fun jsonCustomizer(): Jackson2ObjectMapperBuilderCustomizer = Jackson2ObjectMapperBuilderCustomizer { builder ->
    builder.modules(KotlinModule.Builder().build(), JavaTimeModule())
}

// After: explicit @Primary bean
@Bean
@Primary
fun objectMapper(): ObjectMapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
```

**의사결정 3 — L2 캐시 비활성화**: Cache:Get avg 1,511ms, 총 4,397초가 1위 병목. 코드는 유지, 설정으로 비활성화.

```yaml
# application-local.yml
cache:
  l2:
    enabled: false
```

**결과**: Cache:Get 병목 완전 제거. Slow task #1이 FanOutBatchLoader:Fetch(avg 279ms)로 변경.

#### 4.2 코루틴 병렬 변환

**커밋**: `7c10986c perf(worker): convert processSequentialBatch to coroutine parallel`

**문제**: `processSequentialBatch`의 for-loop 순차 처리. chunk당 3-4개 메시지를 316ms씩 순차 처리 = 948ms.

**의사결정**: for-loop → coroutine `async`/`awaitAll` on `Dispatchers.IO`

```kotlin
// Before: sequential for-loop
private fun processSequentialBatch(messages: List<PgmqMessage<T>>) {
    for (message in messages) {
        val result = executor.executeOrDefault({ calculateOnly(message) }, null, context)
        if (result is CalculationResult) results.add(result)
    }
    batchWrite(results)
}

// After: coroutine parallel
private fun processSequentialBatch(messages: List<PgmqMessage<T>>) {
    val results: List<CalculationResult> = runBlocking(Dispatchers.IO) {
        messages.map { message ->
            async(Dispatchers.IO) {
                executor.executeOrDefault({ calculateOnly(message) }, null, context)
                    as? CalculationResult
            }
        }.awaitAll().filterNotNull()
    }
    batchWrite(results)
}
```

**트레이드오프 분석**:

| 항목 | 순차 | 코루틴 | 이유 |
|------|------|--------|------|
| chunk 처리시간 | 948ms (3×316) | ~350ms | 병렬 처리 |
| Drain 속도 | 35/sec | **90/sec** | 2.5x |
| 요청 throughput | 585 req/s | 336 req/s | -43% (리소스 경합) |
| 개별 task avg | 316ms | 864ms | DB/CPU 경합 증가 |

개별 task는 느려졌지만, 병렬로 더 많은 task를 동시 처리하여 전체 throughput 2.5x 향상.

**채택 이유**: 코루틴의 컨텍스트 스위칭 비용은 Platform Thread 대비 거의 0. 기존 병렬 모드의 문제였던 컨텍스트 스위칭 오버헤드를 제거하면서 병렬화 이점 확보.

---

## Rejected Alternatives

### R1. 아이템 레벨 코루틴 병렬화

**시도**: `d0352eb7` (reverted at `d399c61e`)

프리셋 내 장비 아이템별 코루틴/CompletableFuture 병렬화.

**기각 이유**: 3 presets × 10 items = 30 tasks/request. Thread pool saturation으로 RPS 118→88 회귀. Preset-level 병렬화로 충분.

### R2. Virtual Thread for CPU-bound 계산

**시도**: `cb8f54b7` (reverted at `d399c61e`)

아이템 계산 executor를 VT로 전환.

**기각 이유**: ProbabilityConvolver의 CPU-bound 합성곱 연산이 carrier thread를 pinning. 3.5x latency 회귀 확인. CPU-bound 작업은 Platform Thread 필수.

### R3. L2 캐시 유지

**검토**: Cache:Get avg 1,511ms를 개선하려 batch L2 도입 (#750).

**기각 이유**: batch로 개별 쿼리는 감소했으나 L2 자체의 네트워크 왕복 + 직렬화 오버헤드가 근본적으로 해결 안 됨. L1(Caffeine)만으로 충분한 hit rate 확보. 설정으로 비활성화 후 코드는 보존.

### R4. Jackson2ObjectMapperBuilderCustomizer

**검토**: Spring Boot의 표준적인 방식으로 KotlinModule 등록.

**기각 이유**: Spring Boot 3.5.4에서 `builder.modules()` 호출이 실제 ObjectMapper에 반영되지 않음. `@Bean @Primary ObjectMapper` 직접 생성으로 해결. Module bean 자동 감지도 신뢰할 수 없었음.

### R5. CallerRunsPolicy / BlockingSubmitExecutor

**검토**: ThreadPool reject 시 정책 변경으로 태스크 손실 방지.

**기각 이유**: 근본 원인은 executor 정책이 아니라 과병렬화. FixedThreadPool + sequential batch로 근본 해결. BlockingSubmitExecutor는 spin-wait로 CPU 낭비만 추가 (`31d8025a`로 제거).

---

## Consequences

### 긍정적

1. **Drain 속도 27x 향상**: 3.3/sec → 90/sec. 10K 부하 테스트 큐 드레인이 200초 내 완료 가능
2. **503 에러율 0%**: 98.4% → 0%. 모든 요청이 202 Accepted로 큐잉
3. **DB 부하 최적화**: HikariCP pending=0, timeout=0. Bulk JDBC + batch write로 왕복 최소화
4. **계산 중복 제거**: BatchComputeBuffer dedup hit rate 90%. 실제 계산량 1/10 감소
5. **파싱 최적화**: parseSinglePreset()으로 불필요한 프리셋 파싱 제거

### 부정적

1. **요청 throughput 저하**: 585 → 336 req/s. 코루틴이 서버 리소스를 더 사용
2. **개별 task latency 증가**: 316ms → 864ms. 병렬 경합으로 개별 느려짐 (전체 throughput은 향상)
3. **L2 캐시 비활성화**: 인스턴스 재시작 시 콜드 캐시. 다중 인스턴스 환경에서 캐시 불일치 가능
4. **코루틴 복잡도**: runBlocking + Dispatchers.IO 도입으로 디버깅 난이도 증가

### 향후 고려사항

1. **chunk당 동시성 제한**: 코루틴에 Semaphore를 추가하여 DB 커넥션 풀 보호
2. **L2 캐시 재도입**: Cache:Get 병목의 근본 원인(직렬화 오버헤드) 해결 후 재활성 검토
3. **request throughput 복구**: 코루틴 디스패처 튜닝으로 요청 처리와 워커 처리의 리소스 분리
4. **ProbabilityConvolver 슬롯 병렬화**: 유일하게 코루틴이 추가 이득을 줄 수 있는 CPU-bound 영역. 슬롯 3개라 10-20% 제한적

---

## PR 타임라인

| PR | 날짜 | 제목 | 핵심 변경 |
|----|------|------|----------|
| #729 | 04-19 | Pipeline architecture | ConcurrentQueue + Scheduled Drain |
| #730 | 04-19 | Pipeline two-phase batch | Phase 1 calc + Phase 2 batchWrite |
| #733 | 04-22 | Semaphore(64) 제거 | 무의미한 gate 제거 |
| #734 | 04-22 | Bulk JDBC upsert | Per-row JPA → batch JDBC |
| #735 | 04-22 | VT → FixedThreadPool | **최대 임팩트**: carrier pinning 해결 |
| #738 | 04-22 | BlockingSubmitExecutor 제거 | Spin-wait wrapper 제거 |
| #739 | 04-22 | PipelineBuffer backpressure | Capacity 자동 정렬 |
| #749 | 04-22 | Compute key dedup + Sequential batch | 90% 중복 계산 제거 |
| #750 | 04-22 | Batch L2 cache | 개별 L2 쿼리 → batch (후에 L2 제거) |
| #753 | 04-22 | parseSinglePreset + presetNo | 3프리셋 → 1프리셋 파싱 |
| #754 | 04-23 | Jackson fix + L2 비활성화 + 코루틴 | KotlinModule 수정 + 2.5x drain |

---

## 참조

- `docs/05_Reports/SLOW_TASK_ANALYSIS.md` — 초기 병목 분석 (CalculateOnly 25초)
- `docs/05_Reports/05_06_Load_Tests/2026-04-22-pipeline-perf-iteration.md` — 단계별 부하 테스트 결과
- `docs/01_ADR/ADR-pipeline-fan-out-restructuring.md` — FanOut 구조 개편 ADR
- `docs/01_ADR/ADR-pgmq-atomic-dedup-monotonic-upsert.md` — Dedup + monotonic upsert ADR
- `docs/06_Performance_Journey/calculation-parallelization-experiment.md` — 아이템 병렬화 실패 기록
- `module-infra/.../pgmq/PgmqWorker.kt` — 코루틴 변환 대상 파일
- `module-app/.../parser/EquipmentStreamingParser.java` — parseSinglePreset 구현
- `module-infra/.../config/JacksonConfig.kt` — @Primary ObjectMapper 수정
