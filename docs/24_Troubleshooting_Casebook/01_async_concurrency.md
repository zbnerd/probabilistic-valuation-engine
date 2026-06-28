# 01. 비동기·동시성·가상스레드·락

> CompletableFuture 체이닝, virtual-thread pinning, 분산락 예외 전파, backpressure, 코루틴 의존성.
> 비동기 계약을 깨뜨리는 패턴들이 만든 장애들.

---

## 1-1. 비동기 CF 체인 중 `.get()`/`.join()` blocking → 가상스레드 carrier pinning

- **Session:** 20260618-121324-3985962 (+ 20260618-150610, 20260618-231729, 20260617-053345)
- **문제/에러:** `LogicExecutor.execute`/`Lock.execute`/`SingleFlight`/`TieredCache` 가 동기 `T` 반환. caller 가 `ThrowingSupplier { task.get() }` 로 언래핑. PGMQ worker·controller·scheduler 가 체인 중 block → 가상스레드 carrier pinning(`synchronized`+blocking), worker-pool 고갈, backpressure 무력화, 부하테스트 비결정적 저하(wait-bound). `module-infra`/`module-external-api` 에서 24 CRITICAL + 6 HIGH call-site.
- **원인:** core port 가 동기 반환 계약 → 모든 caller 가 blocking bridge 강제. 비동기 경로 도달 불가.
- **해결:** `*Async` API big-bang 마이그레이션(`executeAsync`/`executeWithLockAsync`/`getAsync`/`putAsync`), ~15 caller 파일. `PgmqWorker.processAsync`/`ProcessOutcome`(Ack|Nack|DeadLetter) sealed class. `InternalApiController` `.join()` 제거(fire-and-forget). `ChunkFileManager`/`Sink.closeAsync` 의 `all.get(10min)` 제거. 회귀 방지 CI grep gate(`runBlocking`/`.join(`/`.get(`/`Thread.sleep(` 금지). ADR `external-api-worker-cf-chaining`, `blocking-async-contract-cf-chain`.
- **왜 이 방법 / 대안:** 동기 반환 삭제 선택 — API surface 축소, 오용이 compile error, grep gate 가 회귀 차단. big-bang 은 port-별 shim 을 두면 `.get()` 이 여전히 leak 되기 때문. **기각:** port-별 호환 shim(footgun 잔존), ADR-010 outbox timing 별도 추적(Non-Risk 선언).

---

## 1-2. `LockAspect.handleLockFailure` 가 `DistributedLockException` 삼킴 → fail-open 미작동

- **Session:** 20260618-121324-3985962
- **문제/에러:** `executeWithLockAsync` + `cf.get()` 마이그레이션 후, AOP failure handler 의 `if (e is DistributedLockException)` 가 매치 안 됨. `cf.get()` 이 원본 `DistributedLockException` 을 `ExecutionException` 으로 wrap → caller 가 `InternalSystemException` 수신, 의도한 `proceedWithoutLock` fallback 미발동. 부수: `PostgresAdvisoryLockStrategyAsyncTest` `InvalidUseOfMatchersException`, 운영 `whenComplete` NPE(mock 이 `*Async` method 에 null 반환).
- **원인:** (1) AOP handler 가 `ExecutionException.cause` 언래핑 누락; (2) test mock 이 신규 async signature 에 맞춰지지 않아 null 반환 → 운영 `whenComplete` continuation NPE.
- **해결:** `LockAspect.kt` 가 `is DistributedLockException` 체크 전 `ExecutionException.cause` 언래핑(commit `08c972ba0`). `PostgresAdvisoryLockStrategyAsyncTest` Mockito matcher 수정(`abce52060`), `*Async` Lock API 용 unit-test mock 갱신(`ab1c151a0`). `PostgresLockStrategy.unlockInternal` 상단 session-registry cleanup 추가(`*Async` path leak 방지).
- **왜 이 방법 / 대안:** JDK wrap 예외 언래핑이 fail-open semantics 보존하는 최소 fix. **기각:** `cf.get()` → 비throwing `cf.handle` 전환(모든 caller error path 재작성 필요, 마이그레이션 중 너무 침습적). 락 모델은 ADR-057(Redisson)/ADR-318(PostgreSQL advisory).

---

## 1-3. `BackpressureLimiter` cancel-safety 결함 + `runBlocking` 잔초 (전 모듈)

- **Session:** 20260619-050643-2314642 (2,411 tool calls), 20260619-061917-2513672
- **문제/에러:** 코드베이스 전수 audit(`docs/05_Reports/2026-06-18-blocking-audit.md`) 이 module-synchronizer/consumer/ranking, ext-api/snapshot/urgent, infra/pgmq/worker, infra/lock 의 blocking primitive 적발. `BackpressureLimiter` cancel-safety 구멍, hot path `runBlocking` leak, `OrderedLockExecutor`/`PostgresAdvisoryLockStrategy` commonPool 사용.
- **원인:** 가상스레드 executor 위 mixed blocking primitive; 락 예외 `ExecutionException` wrap 미언래핑; async lock 에 commonPool 사용.
- **해결:** `BackpressureLimiter` cancel-safety + consumer/ranking `runBlocking` 정리(commit `8b56bdce9`). 락 직렬 fix(`776db9a23`, `65ca65872`, `185c1dcdd`) — `PostgresAdvisoryLockStrategy` 에 `defaultAsyncExecutor` 주입(commonPool 제거), `LockAspect` `ExecutionException.cause` 언래핑. 회귀 방지 CI grep gate(`2edf07dc6`, `a801ba601`). ADR-393, ADR-blocking-async-contract-cf-chain, blocking-audit report.
- **왜 이 방법 / 대안:** CI grep gate(모듈별 `BlockingPrimitiveGateTest`)가 no-regression 계약을 저비용으로 시행. runtime instrumentation 대신 광역 파일 커버리지 우선.

---

## 1-4. `OcidLookupPhase` 무한 재귀 → `StackOverflowError`

- **Session:** 20260610-084740-679261
- **문제/에러:** rate-limiter bucket 이 refill window 보다 빨리 drain 될 때 `StackOverflowError`. 리팩터 `0344c62b7` 가 `while { ... continue }` 를 `private suspend fun processBatch(...)` 자기호출로 변환. `permits==0` 일 때 재귀 — coroutine suspension point 없이 stack frame 적재 → overflow.
- **원인:** 재귀 호출에 suspension point 없어 stack 적재. `acquirePermits` 가 동기라 yield 없으면 dispatcher 굶주림.
- **해결:** `processBatch` body 를 `while (current < igns.size)` 로 되돌리고 `permits==0` 시 `yield()`(`delay(100)` 아님). `suspend fun` signature 유지(`ExternalApiScheduler` 의 `runBlocking { ocidLookupPhase.execute(...) }` 호환). commit `0a8ef7bab`. 검증: 599,800 IGN @ ~402 files/s, 0 StackOverflowError.
- **왜 이 방법 / 대안:** `yield()` 가 tight-spin/재귀 대신 coroutine suspend. 반복문은 #1128 의 recursion 패턴 되돌리되 suspend contract 유지.

---

## 1-5. Calculator Kafka listener 사망 (`NoClassDefFoundError`) — coroutines-reactor 의존성 삭제

- **Session:** 20260611-021434-6530
- **문제/에러:** Calculator `@KafkaListener`(`KafkaSnapshotChunkReadyConsumer.consume/consumeUrgent`) `suspend fun` 선언이 첫 메시지에 `NoClassDefFoundError` → `calculator-snapshot-chunk-processor` listener 자가 정지.
- **원인:** commit `9fbea109f` 가 "version catalog 미해결" 로 `libs.kotlinx.coroutines.reactor` 삭제. Spring Kafka `KotlinAwareInvocableHandlerMethod` 가 `suspend`→reactive bridge 를 `kotlinx.coroutines.reactor.MonoKt` 로. OCID→char-basic 경로가 Kafka chunk 발행전까지 latent.
- **해결:** 의존성 1.9.0 복구(타 coroutines module 일치). commit `4e5459a57`.
- **왜 이 방법 / 대안:** 대안 없음 — suspend→reactive bridge 는 Spring Kafka hard requirement. 원 삭제는 false-positive version-catalog 정리; 최소 복구가 listener 재활성화.

---

## 1-6. RankingFetch backpressure 우회 — submission CF 미대기, sink 조기 close

- **Session:** 20260610-143941-1012841, 20260610-154158-1084574
- **문제/에러:** page-1 record 가 `sink.close()` 이후 submit 됨 — `thenAcceptAsync + inner whenComplete`(fire-and-forget) 로 submission CF 가 outer chain 에서 미대기; submission error 삼킴.
- **원인:** async composition 이 `sink.close()` 를 submission 보다 앞서게 race. #1217 의 backpressure 가 실제 미작동.
- **해결:** `thenComposeAsync + outer whenComplete` 로 submission chain+대기; error 가 outer handle 로 전파→실패 기록. commit `1c54202ac`. 동 세션 post-merge test fixture 5건(KeyTypes, JavaTimeModule, FQN event class) 수리.
- **왜 이 방법 / 대안:** `thenCompose` 가 future chain(backpressure 보존) vs `thenAccept` fire-and-forget(ordering 상실).
