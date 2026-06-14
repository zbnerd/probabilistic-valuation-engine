# ADR-724: ForkJoinPool Saturation 시 Dedicated CPU Executor 분리 결정

- Status: Accepted (provisional — #1198 saturation metric 실측 후 trigger 활성)
- Date: 2026-06-08
- Owner: zbnerd
- Related: ADR-723 (IO/CPU split pattern), #1198 (ForkJoinPool metrics), Issue TBD

---

## 1. Background / Problem

### Background

- 4 module (external-api, synchronizer, calculator, rest-controller + infra) 이 `Dispatchers.Default` 공유 (JVM-wide `ForkJoinPool.commonPool()`).
- Issue #1198 가 `ForkJoinPool.commonPool().activeThreadCount` 를 Prometheus 로 노출 (3 Gauges).
- ADR-723 §4 의 trigger: `activeThreadCount > coreCount * 2` 지속 시 dedicated executor 분리 검토.

### Problem

`t3.small` prod (2 vCPU) 에서 4 module 동시 high traffic 시 `ForkJoinPool.commonPool()` 의 parallelism = CPU core count - 1 = 1. `activeThreadCount` 가 saturation 되면 cross-module 영향.

### Goal

Saturation trigger 조건 만족 시 dedicated CPU executor 분리 결정. Module 별 dedicated `ThreadPoolTaskExecutor` (or `CoroutineDispatcher` bean) 으로 격리.

---

## 2. Decision

> **Saturation metric (`forkjoinpool.active.threads > coreCount * 2` 지속 5min) 시 module 별 dedicated CPU executor 분리. Cross-module 영향 차단.**

### 구현 (trigger 활성 시)

```yaml
# application.yml
executor:
  external-api-cpu:
    core-pool-size: ${availableProcessors:4}
    max-pool-size: ${availableProcessors:8}    # 1:2 ratio
    queue-capacity: 1000
    thread-name-prefix: external-api-cpu-
  synchronizer-cpu:
    core-pool-size: ${availableProcessors:4}
    max-pool-size: ${availableProcessors:8}
    queue-capacity: 1000
    thread-name-prefix: synchronizer-cpu-
  calculator-cpu:
    core-pool-size: ${availableProcessors:4}
    max-pool-size: ${availableProcessors:8}
    queue-capacity: 1000
    thread-name-prefix: calculator-cpu-
  rest-controller-cpu:
    core-pool-size: ${availableProcessors:4}
    max-pool-size: ${availableProcessors:8}
    queue-capacity: 1000
    thread-name-prefix: rest-controller-cpu-
```

각 module 의 `parseDispatcher` / `calcDispatcher` / `executeWith` 등에서 named bean (`@Qualifier("external-api-cpu")` 등) 또는 `Dispatchers.IO.limitedParallelism(parallelism)` 으로 wired.

### Sizing (per #1128 precedent)

| Pool | core | max | queue | rationale |
|---|---|---|---|---|
| `external-api-cpu` | CPU | 2*CPU | 1000 | IO-bound mixed |
| `synchronizer-cpu` | CPU | 2*CPU | 1000 | file parse + serialize |
| `calculator-cpu` | CPU | 2*CPU | 1000 | calculate |
| `rest-controller-cpu` | CPU | 2*CPU | 1000 | controller dispatch |

`1:2 ratio` (P2-25) 준수.

---

## 3. Trade-offs

### Sensitivity

- `ForkJoinPool.commonPool()` 의 `activeThreadCount` — saturation trigger 의 primary signal.
- Cold-miss 부하테스트 (RESET_VIEWS=1 + RESET_ACTIVE_JOBS=1) — saturation 실측 trigger.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
|---|---|---|
| `Dispatchers.Default` 단일 유지 (ADR-723 결정) | 단순성, 0 코드 변경 | saturation 시 cross-module 영향 |
| Module 별 dedicated executor | 격리, 모니터링 가능, saturation 시 dedicated | 관리 비용, 4 module 별 yaml + 빈 정의 |
| (rejected) `Executors.newVirtualThreadPerTaskExecutor()` per module | VT carrier | ItemCalculationExecutorConfig 3.5x latency regression 위배 |

### Risk

* **Saturation false positive** — 일시적 spike 가 trigger 잘못 발동. 5min 지속 threshold 로 완화.
* **Dedicated executor 의 pool saturation** — 자체 pool 도 saturation 가능. 동일 metric 으로 monitoring.
* **Module 간 cross-call 영향** — A module 의 executor 가 B module 의 호출 시 B 가 blocking 가능. YAGNI 원칙 위배 시 re-design.

### Non-Risk

* **Backward compat** — `parse-dispatcher: default` YAML 은 여전히 `Dispatchers.Default` 사용. Module 변경 시 명시적 bean name 으로 override.
* **ADR-723 §23.6 PR review checklist** — `grep "Dispatchers.Default.asExecutor"` 로 dedicated bean 으로의 migration 진행 추적 가능.

---

## 4. Result / Evidence

### Metrics (예측)

| Metric | Baseline (Dispatchers.Default 단일) | Target (dedicated 후) |
|---|---|---|
| `forkjoinpool.active.threads` | 1-4 (t3.small) | N/A (dedicated pools use own thread count) |
| `forkjoinpool.queued.tasks` | spike 시 100+ | N/A |
| `executor.completed / external-api-cpu` | N/A | 100% / 5min |
| Latency p99 (cold-miss) | +20% (saturation) | baseline (격리) |

### 검증 절차

1. `curl http://localhost:8081/actuator/prometheus | grep forkjoinpool` — 3 hits
2. `curl http://localhost:8081/actuator/prometheus | grep -E "external-api-cpu|synchronizer-cpu"` — 4 hits (post-구현)
3. Cold-miss 부하테스트: `active.threads > coreCount * 2` 5min 지속 시 Grafana alert 발동. Alert 발생 시 dedicated executor 적용 결정.

### 결과 (구현 후)

* Module 격리로 cross-module 영향 차단.
* 각 module 의 saturation 독립적 monitoring 가능.
* Saturation 시 영향받는 module 만 degradation, 전체 시스템 OK.

---

## 5. Summary

> **Saturation metric trigger (`forkjoinpool.active.threads > coreCount * 2` 5min 지속) 시 module 별 dedicated CPU executor 분리. Cold-miss 부하테스트 + Prometheus alerting 으로 trigger 활성.**

---

## Related

- ADR-723: `docs/01_ADR/ADR-723_io-cpu-split-pattern.md`
- Issue #1198: ForkJoinPool saturation metric (구현됨, PR #1213)
- Issue TBD: dedicated executor 구현 (trigger 활성 후)
- Predecessor issues: #1125, #1127, #1128, #1129, #1130, #1131 (모두 merged)
- Hot paths: `module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt`, `module-infra/.../ExecutorConfig.kt`
