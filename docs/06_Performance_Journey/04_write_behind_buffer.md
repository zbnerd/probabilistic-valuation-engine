# 4장: DB 저장이 발목을 잡다

> "계산은 100ms인데, DB 저장에 150ms가 걸린다. 비율이 이상하지 않은가?"

## 문제: 동기 DB 저장의 비용

555 RPS를 달성했지만 p50 지연이 871ms였다. 캐시 히트는 4ms인데 전체 평균이 왜 이렇게 높은가?

분석 결과, **캐시 미스 시의 동기 DB 저장**이 원인이었다:

```
캐시 미스 시 요청 흐름 (Before):
Request → Nexon API (257ms) → 파싱 (50ms) → 계산 (100ms) → DB 저장 (150ms) → Response
                                                                  ↑
                                                            여기가 병목!
```

프리셋 3개 × DB 저장 50ms = 150ms. 사용자는 DB 저장이 끝날 때까지 기다려야 했다.

## 고민: DB 저장을 비동기로

세 가지 방안을 검토했다:

**A안: CompletableFuture로 비동기 저장**
- 구현은 간단하지만 실패 시 데이터 유실 위험

**B안: Write-Behind Buffer**
- 메모리 버퍼에 모았다가 배치로 DB 저장
- 복잡하지만 안전하고 효율적

**C안: 메시지 큐 (Kafka/RabbitMQ)**
- 가장 견고하지만 인프라 추가 비용

비교:

| 방안 | 응답 지연 단축 | 데이터 유실 위험 | 복잡도 | 추가 인프라 |
|------|---------------|-----------------|--------|------------|
| A. 비동기 | 150ms→0ms | 높음 (OOM, 크래시) | 낮음 | 없음 |
| **B. Write-Behind** | **150ms→0.1ms** | **낮음 (Phaser)** | **중간** | **없음** |
| C. 메시지큐 | 150ms→5ms | 매우 낮음 | 높음 | Kafka 필요 |

B안을 선택했다. 이유:
1. 추가 인프라 없이 구현 가능
2. Phaser 기반 graceful shutdown으로 데이터 유실 방지
3. CAS + Exponential Backoff로 lock-free 동시성 제어

## 구현: Write-Behind Buffer

```
Before (동기):
Request → Calculate → DB Save (150ms) → Response

After (비동기 버퍼):
Request → Calculate → Buffer.offer (0.1ms) → Response
                              │
                              ▼ (백그라운드 배치)
                         DB Batch Save
```

### 핵심 코드

**Phaser 기반 Shutdown Safety** — 서버 종료 시 버퍼에 남은 데이터를 안전하게 flush:

```kotlin
private val shutdownPhaser = Phaser()

fun offer(tasks: List<ExpectationWriteTask>): Boolean {
    if (shuttingDown) return false
    shutdownPhaser.register()
    return executor.executeWithFinally(
        { offerInternal(tasks) },
        { shutdownPhaser.arriveAndDeregister() },
        TaskContext.of("Buffer", "Offer", "tasks=${tasks.size}")
    )
}
```

**CAS + Exponential Backoff** — lock-free로 pending count 관리:

```kotlin
for (attempt in 0 until properties.casMaxRetries()) {
    if (pendingCount.compareAndSet(current, current + required)) {
        return true  // 성공
    }
    backoffStrategy.backoff(attempt)  // 1ns, 2ns, 4ns...
}
```

Backpressure도 구현했다. 버퍼가 10,000개 이상 쌓이면 신규 offer를 거부:

```kotlin
if (pendingCount.get() >= backpressureLimit) {
    meterRegistry.counter("buffer.rejected.backpressure").increment()
    return false
}
```

## 결과: 674 RPS (+21%)

2026년 1월 25일:

```
╔════════════════════════════════════════════════════════════╗
║  V4 WRITE-BEHIND BUFFER                                    ║
║  wrk 100 connections:                                      ║
║  - RPS:       674 (+21% vs 555)                            ║
║  - Error:     0% (이전 1.4~3.3%)                           ║
║  - Avg Latency: 163.89ms                                   ║
║  wrk 200 connections:                                      ║
║  - RPS:       719                                          ║
║  - Avg Latency: 275.17ms                                   ║
╚════════════════════════════════════════════════════════════╝
```

### 핵심 변화

| 지표 | Before | After | 개선 |
|------|--------|-------|------|
| DB Write Latency | 150ms (동기) | 0.1ms (비동기) | **1,500배** |
| 에러율 | 1.4~3.3% | 0% | **완전 제거** |
| 버퍼 처리량 | - | 500개/5초 배치 | 신규 |

## 새로운 문제

에러율이 0%가 되었다. 좋은 신호. 그런데 프로파일링을 해보니 또 다른 병목이 보였다:

**프리셋 계산이 순차 처리**되고 있었다. 3개 프리셋을 for 루프로 하나씩 계산:

```kotlin
for (presetNo in 1..3) {
    val preset = calculatePreset(equipmentData, presetNo)
    results.add(preset)
}
// 100ms × 3 = 300ms
```

3개를 동시에 계산하면 100ms면 된다. 3배 빨라질 수 있다.

하지만 함정이 있었다: 같은 Executor에서 부모-자식 태스크를 실행하면 **데드락**이 발생할 수 있다. P0-3에서 이미 발견한 Anti-pattern이었다.

---

> **이 시점의 RPS: 674 (이전 555 대비 +21%)**
> **커밋**: `db7f3f99` feat: V4 API 병목 해소 - 프리셋 병렬 계산 + Write-Behind 버퍼 (#266)
> **관련 이슈**: #266
> **PR**: Issue #266 (프리셋 병렬 계산 + Write-Behind)

**다음 장**: [5장 — 3개를 한 번에: 병렬 계산의 힘](./05_parallel_presets.md)
