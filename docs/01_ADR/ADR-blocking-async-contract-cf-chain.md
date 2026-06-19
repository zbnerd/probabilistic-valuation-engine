# ADR: Blocking Async Contract → Pure CF Chain

- Status: Proposed
- Date: 2026-06-18
- Owner: TBD
- Supersedes: (none)

---

## 1. Background / Problem

### Background

`module-external-api` 와 `module-infra` 의 비동기 호출 체인이 `CompletableFuture` / `Mono` chain 이어야 하지만, 다수의 호출 지점에서 `.get()` / `.join()` / `runBlocking` / `Job.join()` 같은 blocking primitive 가 사용되어 async contract 가 깨지고 있다. `module-infra` 의 핵심 port (`LogicExecutor`, `Lock`, `SingleFlight`, `TieredCache`) 가 **synchronous `T` return** contract 를 가지며, 내부에서 `ThrowingSupplier { task.get() }` 같은 bridge 로 CF 를 강제로 unwrap 하고 있어, 어떤 caller 도 non-blocking 으로 통과할 수 없다.

### Problem

PGMQ worker, VT worker, controller, scheduler 모두 chain 중간에서 blocking 되면서:
- Virtual thread carrier pinning 위험 (synchronized + blocking)
- Worker pool exhaustion under load
- Async backpressure 무력화 (slow upstream → caller 도 slow)
- Load test throughput 의 비결정적 저하 (CPU-bound 가 아닌 wait-bound)

총 24 CRITICAL + 6 HIGH site 식별. module-core / module-common 은 scan 결과 clean (0 hits).

### Goal

`module-external-api` 진입점부터 `module-infra` 의 cache / lock / single-flight / worker 끝까지 **단 한 번의 blocking 호출도 없는 pure `CompletableFuture<T>` chain** 으로 만든다. `LogicExecutor.execute(...)` 같은 sync return API 는 **모두 삭제**하고 `executeAsync(...)` 로 일원화한다.

---

## 2. Decision

> 우리는 `LogicExecutor`, `Lock`, `SingleFlight`, `TieredCache` 의 sync return contract 를 모두 제거하고, `CompletableFuture<T>` 만 반환하는 `*Async` API 로 일원화한다. 같은 PR 에서 모든 caller (`module-external-api`, `module-calculator`, `module-synchronizer`, `module-rest-controller`) 가 `executeAsync` / `executeWithLockAsync` / `getAsync` / `putAsync` 로 마이그레이션한다. Backward-compat shim 은 두지 않는다 (big-bang).

```text
HTTP controller
    └─► CF<RunKey>            (InternalApiController:83, 123)
         └─► CF<RunKey>       (ExternalApiScheduler.triggerPhaseAsync)
              └─► CF<Void>    (PhaseLoopController.submitIterationAsync)
                   └─► CF     (loopExecutor VT submit, non-blocking)
                        └─► CF<List<Chunk>>     (PgmqWorker.handleAsync)
                             └─► CF<ItemData>   (EquipmentFetchProvider.fetchAsync)
                                  └─► CF<T>     (TieredCache.getAsync)
                                       └─► CF<T> (SingleFlight.executeAsync → leader loader CF)
                                            └─► CF<ByteArray> (NexonExternalApiClientAdapter)
                                                 └─► WebClient.get().bodyToMono().toFuture()
```

---

## 3. Trade-offs

### Sensitivity

* **Caller fan-out** — `module-calculator` / `module-synchronizer` / `module-rest-controller` 의 모든 `LogicExecutor.execute` / `Lock.execute` / `SingleFlight.execute` / `TieredCache.get` / `TieredCache.put` 호출 지점. 약 15+ 파일.
* **PGMQ worker thread model** — `PgmqWorker.run()` 의 VT carrier 위에서 `handle()` 이 CF chain 을 끝까지 await 하지 않고 fire-and-forget 으로 제출해야 함. Thread pool sizing 영향.
* **Spring `@Cacheable` 제거** — 기존 sync AOP 프록시 기반 cache 가 사라지고 boundary caller 가 `getAsync` / `putAsync` 로 wrap. Spring AOP advisor 빈 정리에 영향.
* **DB connection pool (HikariCP)** — async chain 이 길어질수록 in-flight tx 점유 시간 증가. `maximumPoolSize` 재조정 필요할 수 있음.
* **WebClient / Reactor Netty** — 이미 async. chain 합류 지점에서 `Mono → CF` 브리지 (`toFuture()`) 그대로 유지.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Sync return 완전 삭제 | Caller 가 non-blocking 으로 통과 가능. Backpressure 실제 작동. `ThrowingSupplier { task.get() }` 같은 bridge 코드 0. | Big-bang PR. Caller fan-out (~15 파일) 동시 변경. `@Cacheable` AOP 프록시 의존 코드 직접 wrap. |
| `*Async` 단일 API (no sync shim) | API surface 작음. caller 잘못 쓰면 compile error. | 점진적 마이그레이션 불가. 한 PR 이 커짐. |
| CI grep gate 추가 | 향후 회귀 방지. | Gradle test 추가 작업, CI 1~2초 증가. |
| Per-port atomic commit in single PR | Review 가능. 중간 상태에서도 grep gate 가 `.get()` 누락 차단. | Commit 수 많음 (예상 6-10). |

### Risk

* **Big-bang PR 의 merge conflict 가능성** — `module-infra` 동시 변경 시. Mitigation: feature branch + 1일 freeze on infra-touching files.
* **Caller 측 실수로 `.get()` 또는 `.join()` 이 다시 들어옴** — 특히 `@Transactional` AOP 안에서. Mitigation: grep gate + per-call-site review.
* **Spring `@Cacheable` AOP 제거로 cache miss rate 잠시 증가** — boundary wrap 시 L1 / L2 호출 순서 실수. Mitigation: TieredCache 내부 상태 machine + unit test.
* **PGMQ worker 의 fire-and-forget CF 가 parent chain 의 cancel 과 무관하게 계속 진행** — caller 가 cancel 해도 worker 가 끝까지 실행. Mitigation: `CF.whenComplete` 에서 `CancellationException` 처리, ack 전 cancel 시 visibility reset.

### Non-Risk

* **Lock contention** — `pg_try_advisory_xact_lock` 그대로. tx-scoped 보장.
* **Cache staleness** — L1 / L2 invalidation 전략 (Postgres NOTIFY) 변경 없음.
* **Single-flight semantics** — leader / follower 분기 동일. lock 해제 시점 = task 완료 후 그대로.

---

## 4. Result / Evidence

### Metrics

| Metric | Before | Target | Notes |
| ------ | ----: | ----: | ----- |
| Blocking primitive count (`module-infra` + `module-external-api` main) | 24 CRITICAL + 6 HIGH | **0** | grep gate |
| `LogicExecutor.execute` (sync) callers | N (TBD scan) | **0** | compile gate |
| `Lock.execute` (sync) callers | N (TBD scan) | **0** | compile gate |
| Load test `views_per_sec` | baseline (capture pre-PR) | ≥ baseline | `RESET_ACTIVE_JOBS=1 RESET_VIEWS=1 COUNT=10000` |
| PGMQ `q_result_ready_queue` drain time | baseline | ≤ baseline | load test observation |
| `module-calculator` / `module-synchronizer` / `module-rest-controller` unit test | all green | all green | `./gradlew test` |

### Observed Result

* (측정 예정, PR 머지 전 baseline 캡처)

---

## 5. Summary

> `LogicExecutor` / `Lock` / `SingleFlight` / `TieredCache` 의 sync return 을 전부 제거하고 `*Async` API 로 일원화. 같은 PR 에서 `module-external-api` 진입점 ~4 site + `module-infra` worker ~7 site + 모든 caller (~15 파일) 를 `CF` chain 으로 마이그레이션. CI grep gate 로 회귀 차단. Backward-compat shim 없음.
