# Issue #998: ExternalApiScheduler acquireLock timeout → exception

- Issue: https://github.com/.../issues/998 (module-external-api)
- Date: 2026-06-04
- Status: Proposed

---

## 1. Background / Problem

### Background

`ExternalApiScheduler` 는 in-process `ReentrantLock` + `Condition` 으로 한 JVM 안에서 파이프라인 동시 실행을 직렬화한다. 두 진입점이 같은 락을 두고 경합한다:

- `triggerDailyRefresh()` — `cron` 또는 `onStartup` 에서 호출, 1h timeout
- `runItemEquipmentCycle()` — internal cycle, 120s timeout

### Problem

`acquireLock(timeoutMs)` 가 락 획득 실패 시 `false` 를 반환한다. 호출부는 두 군데:

- `triggerDailyRefresh` (line 63): `false` 시 `log.warn` + `return`. **전체 daily refresh 가 조용히 스킵** → 운영자가 알람을 받을 수 없음.
- `runItemEquipmentCycle` (line 150): `false` 시 5초 sleep 후 재귀 호출. **무한 재시도 루프** → 한 번 stuck 되면 5초 간격으로 thread/CPU 만 소모.

`false` 반환 패턴은 "락이 일시적으로 안 잡힌 정상 상황" 과 "다른 인스턴스/사이클이 stuck/deadlock 인 비정상 상황" 을 구분하지 못한다.

### Goal

락 획득 타임아웃을 명시적 도메인 예외로 변환하여 호출부가 alert / metric / bounded retry 로 대응할 수 있게 한다.

---

## 2. Decision

`acquireLock` 은 `Boolean` 대신 `DistributedLockException` 을 throw 한다. 두 호출부에서 catch 하여 metric + error log + bounded skip/retry 로 처리한다.

```text
acquireLock(timeoutMs)
  ├─ lock acquired     → return (caller continues)
  └─ timeout exceeded  → throw DistributedLockException("ExternalApiScheduler", cause=null)

callers
  ├─ triggerDailyRefresh    → catch → log.error + metric.increment(skip reason=lock_timeout) + return (no resubmit)
  └─ runItemEquipmentCycle  → catch → log.error + metric.increment + schedule single retry after 60s (NOT recursive loop)
```

### Why `DistributedLockException` not a new type

`module-common/.../error/exception/DistributedLockException` 가 이미 존재한다 (`ServerBaseException` + `CommonErrorCode.DATABASE_TRANSACTION_FAILURE`). Scheduler 의 in-process lock 은 "다중 인스턴스/다중 사이클 간 분산된 실행 권한" 의미이므로 의미적으로 일치. `errorCode` 는 scheduler 컨텍스트와 어울리지 않지만, 새 예외 타입을 만들 만큼 핵심 도메인 분기가 다르지 않다 (둘 다 "락 못 잡음" 시그널).

### Why remove the 5-second recursive retry

기존 `runItemEquipmentCycle` 의 `Thread.sleep(5s) + runItemEquipmentCycle()` 패턴은 unbounded recursion 이다. stack overflow / scheduler thread 고갈 위험. 60초 후 단일 재시도로 교체 — bounded, scheduler thread free.

### Why `triggerDailyRefresh` does NOT resubmit

`@Scheduled(cron)` 다음 트리거가 자연스러운 backoff 다. `triggerDailyRefresh` 가 lock timeout 시 즉시 retry 하면 같은 cron tick 안에서 lock 이 풀릴 가능성이 낮고 (다른 stuck instance 가 잡고 있음), 다음 cron 까지 자연스럽게 대기하는 게 안전하다.

---

## 3. Trade-offs

### Sensitivity

* 일별/사이클 동시 실행 빈도 — 낮음 (cron 1일 1회 + manual trigger)
* stuck instance 회복 시간 — 1h (다음 cron) 또는 60s (item equipment retry)
* alert latency — 즉시 (log.error + metric)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| --- | --- | --- |
| `DistributedLockException` 재사용 | 새 코드/예외 타입 0, 기존 error handler 와 호환 | errorCode 가 scheduler 의미와 약간 다름 (DATABASE_TRANSACTION_FAILURE) |
| 60s single retry (item equipment) | unbounded recursion 제거, scheduler thread 안전 | stuck instance 회복이 최대 60s 지연 |
| `triggerDailyRefresh` 즉시 skip | cron backoff 자연 활용, 코드 단순 | 운영자가 cron 변경 전까지 stuck 인지 못할 수 있음 → metric 으로 보완 |

### Risk

* `DistributedLockException` 의 `errorCode=DATABASE_TRANSACTION_FAILURE` 가 monitoring/alerting 에서 DB 장애로 오인될 수 있다. **완화**: error log 에 `[Scheduler] lock timeout` prefix + component name 명시.
* `runItemEquipmentCycle` 의 60s 재시도가 metric 상 "1회 재시도" 로만 보일 수 있다. **완화**: 로그에 `lock_timeout_retry` 마커 + retry counter 별도 증가.

### Non-Risk

* `releaseLock` 의 기존 동작은 변경 없음. acquire 만 변경.
* 다른 모듈은 `ExternalApiScheduler.acquireLock` 를 호출하지 않음 (private 함수).

---

## 4. Result / Evidence

### Metrics (additions)

| Metric | Type | Tags | Notes |
| --- | --- | --- | --- |
| `external_api_scheduler_lock_timeout_total` | Counter | `phase=daily_refresh\|item_equipment` | lock timeout 발생 횟수 |
| `external_api_scheduler_lock_acquired_total` | Counter | `phase=daily_refresh\|item_equipment` | lock 획득 성공 (정상 흐름 확인용) |

### Observed Result (expected)

* 정상 운영 시 `lock_acquired_total` 만 증가.
* stuck instance 발생 시 `lock_timeout_total{phase=item_equipment}` 증가 → Grafana alert 가능.
* `runItemEquipmentCycle` 의 무한 재시도 로그 (5초 간격 warn) 사라짐.

---

## 5. Files Changed

| File | Change |
| --- | --- |
| `module-external-api/.../scheduler/ExternalApiScheduler.kt` | `acquireLock` 시그니처를 `Boolean` → `Unit` (throw). 두 호출부에서 catch + bounded handling. `metrics` 필드 추가 (Counter 2개). |
| `module-external-api/src/main/kotlin/maple/externalapi/config/SchedulerMetrics.kt` (new) | `external_api_scheduler_lock_*` counter 정의. SynchronizerMetrics 와 동일 패턴. |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerLockTest.kt` (new) | lock timeout → exception, 두 호출부 catch 동작 검증. |

## 6. Testing Strategy

* Unit: `lock` 을 mock 으로 항상 점유 상태로 만들어 `acquireLock(timeoutMs=10)` 호출 → `DistributedLockException` 확인. `triggerDailyRefresh` 와 `runItemEquipmentCycle` 가 catch 후 재진입 안 함을 verify (spy).
* 기존 `ExternalApiSchedulerTest` (있다면) 가 있으면 통과 확인.

---

## 7. Summary

> In-process `acquireLock` 의 silent-false 패턴을 `DistributedLockException` 으로 바꾸고, 두 호출부에서 bounded skip/retry 로 교체. 무한 재시도 루프 제거 + 운영 alert 가능.
