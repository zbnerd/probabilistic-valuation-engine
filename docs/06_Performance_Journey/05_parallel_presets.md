# 5장: 3개를 한 번에 — 병렬 계산의 힘

> "순차 300ms를 병렬 100ms로. 간단해 보이지만 데드락의 늪이 있었다."

## 문제: 프리셋 순차 계산

4장에서 DB 저장을 비동기로 돌렸다. 이제 계산 자체가 병목이다:

```kotlin
// 순차 계산: 100ms × 3 = 300ms
for (presetNo in 1..3) {
    val preset = calculatePreset(equipmentData, presetNo)
    results.add(preset)
}
```

각 프리셋은 독립적이다. 프리셋 1의 결과가 프리셋 2에 영향을 주지 않는다. 그렇다면 **동시에 계산**하면 된다.

## 고민: 데드락 위험

병렬화는 간단해 보였지만, 한 가지 함정이 있었다. 기존 코드에 이미 발견된 Anti-pattern:

```kotlin
// 위험: 같은 Executor에서 부모-자식 실행
CompletableFuture.supplyAsync({
    dataResolver.resolveAsync(...).join()  // ← 데드락!
}, expectationComputeExecutor)
```

8개 스레드가 모두 `.join()`으로 대기하면, 새 작업을 스케줄링할 스레드가 없어진다. N03 Thread Pool Exhaustion 카오스 테스트에서 이미 증명된 문제다.

### 해결: 전용 Executor 분리

프리셋 계산용 Executor를 별도로 만들었다:

```kotlin
// presetCalculationExecutor: 별도 스레드풀
@Bean
fun presetCalculationExecutor(): TaskExecutor {
    val exec = ThreadPoolTaskExecutor()
    exec.setCorePoolSize(12)
    exec.setMaxPoolSize(24)
    exec.setQueueCapacity(100)
    return exec
}
```

부모 태스크는 `expectationComputeExecutor`에서, 자식 프리셋 계산은 `presetCalculationExecutor`에서 실행. **물리적으로 분리된 스레드풀**이므로 데드락이 발생하지 않는다.

### 병렬 계산 구현

```kotlin
private fun calculateAllPresets(equipmentData: ByteArray, character: GameCharacter): List<PresetExpectation> {
    val futures = IntStream.rangeClosed(1, 3)
        .mapToObj { presetNo ->
            CompletableFuture.supplyAsync(
                { calculatePreset(equipmentData, presetNo) },
                presetCalculationExecutor  // 전용 Executor
            )
        }
        .toList()
    return futures.stream().map { this.joinPresetFuture(it) }.toList()
}
```

3개 프리셋을 CompletableFuture로 동시에 제출하고, 모두 완료될 때까지 기다린다.

## 추가 작업: JSON DoS 방어

병렬화와 함께 보안 강화도 추가했다. 대형 JSON 페이로드로 인한 DoS를 방지:

```kotlin
objectMapper.factory.setStreamReadConstraints(
    StreamReadConstraints.builder()
        .maxNestingDepth(50)
        .maxStringLength(100_000)
        .maxNameLength(256)
        .build()
)
```

## 결과: 965 RPS (+43%)

2026년 1월 26일, AWS t3.small에서 실제 측정:

```
╔════════════════════════════════════════════════════════════╗
║  V4 ADR REFACTORING (병렬 프리셋 + 리팩토링)                ║
║  wrk -t4 -c100 -d30s (t3.small)                           ║
║                                                            ║
║  - RPS:       965.37                                      ║
║  - p50:       95.02ms                                     ║
║  - p75:       114.11ms                                    ║
║  - p90:       137.40ms                                    ║
║  - p99:       213.56ms                                    ║
║  - Max:       332.37ms                                    ║
║  - Error:     0 (socket errors 모두 0)                     ║
║                                                            ║
║  목표 719 RPS 대비 +34% 초과 달성                           ║
╚════════════════════════════════════════════════════════════╝
```

### 5-Agent Council 최종 판정: 만장일치 PASS

| Agent | Role | 판정 | 근거 |
|-------|------|------|------|
| Blue | Architect | PASS | SOLID 원칙 준수, offerInternal() SRP 분리 |
| Green | Performance | PASS | 성능 목표 달성, CAS 최적화 |
| Yellow | QA Master | PASS | Flaky 방지, CyclicBarrier 동기화 |
| Purple | Auditor | PASS | CLAUDE.md Section 12 준수, LogicExecutor 강제 |
| Red | SRE | PASS | 타임아웃 외부화, TaskContext 로그 추적 |

### 병목 해소 요약

| 병목 | Before | After | 개선 |
|------|--------|-------|------|
| 프리셋 계산 | 순차 300ms | 병렬 100ms | **3배** |
| DB 저장 | 동기 150ms | 버퍼 0.1ms | **1,500배** |
| 전체 요청 | ~450ms | ~100ms | **4.5배** |

## 새로운 문제: Scale-out 딜레마

965 RPS! 단일 인스턴스로는 훌륭한 성과다. 하지만 질문이 생겼다:

> **"서버를 2대, 3대로 늘리면 2,000~3,000 RPS가 되나요?"**

답은 **"아니오"**였다.

현재 아키텍처에서 Write-Behind Buffer는 **인메모리**다. 인스턴스 A의 버퍼와 인스턴스 B의 버퍼가 서로 다르다. 게다가 각 인스턴스의 L1 캐시도 독립적이라, 인스턴스 A에서 업데이트한 데이터가 인스턴스 B에 반영되지 않는다.

```
Instance A: [Buffer] [L1 Cache] → data_version=5
Instance B: [Buffer] [L1 Cache] → data_version=3  ← 오래된 데이터!
```

Scale-out을 하려면 **Stateless** 아키텍처로 전환해야 한다. 하지만 그러면 성능이...

---

> **이 시점의 RPS: 965 (이전 674 대비 +43%)**
> **커밋**: `1061c9e0` refactor: P0/P1 ADR 정합성 리팩토링 (#266)
> **관련 이슈**: #266
> **PR**: Issue #266 (ADR 정합성 리팩토링 + 병렬 프리셋)

**다음 장**: [6장 — 정합성의 대가: 속도를 희생하다](./06_stateless_tradeoff.md)
