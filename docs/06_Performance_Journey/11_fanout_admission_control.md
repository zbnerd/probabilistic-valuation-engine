# 11장: 보이지 않는 폭발 — Fan-Out과 Admission Control

> "가장 위험한 병목은 아직 일어나지 않은 일이다."

## 문제: 아직 오지 않은 장애

10장에서 실데이터 환경에서 7,347 RPS를 달성했다. 33% 하락의 원인도 분석했다. 캐시 invalidation으로 인한 DB fallback, CPU pipeline, Write amplification. 모두 해결 가능한 문제였다.

하지만 팀에게 하나의 불안이 있었다.

> **"사용자 1,000명이 1,000개의 서로 다른 캐릭터를 동시에 조회하면 어떻게 되나요?"**

이 질문에 대한 답이 이 장을 쓰게 했다.

## Fan-Out Explosion이란

### 정상 상황

```
요청 → L1 캐시 hit → 바로 응답 (~4ms)
```

캐시 hit ratio가 98%以上인 상태. 대부분의 요청이 fast path로 처리된다.

### Fan-Out Explosion

```
1000 users × 1000 different OCIDs = 1,000,000 unique keys

Single-Flight가 보호하는 것: 같은 키에 대한 중복 요청
Single-Flight가 보호 못 하는 것: 서로 다른 키의 동시 요청

→ 1000개의 독립적인 cold miss가 동시에 실행
→ 1000 × (API fetch + parse + calc + compress)
→ CPU 즉시 포화
```

SingleFlight는 동일 키의 중복 계산을 막아준다. 같은 캐릭터를 100명이 동시에 조회해도 1번만 계산한다. 하지만 **서로 다른 캐릭터** 1000개를 동시에 조회하면, SingleFlight는 아무런 보호도 하지 못한다.

### Nexon API의 물리적 한계

Nexon API 호출은 이미 비동기(CompletableFuture)로 처리되고 있었다. Resilience4j의 Circuit Breaker + Bulkhead + Retry + TimeLimiter도 모두 적용되어 있었다. 하지만 API 자체의 레이턴시는 피할 수 없다.

```
Nexon API Latency (Prometheus 실측):
- getCharacterBasic: 평균 ~150ms, 최대 572ms
- getItemData:        평균 ~150ms, 최대 379ms
- Fan-Out 완료까지:   ~200ms (병렬 호출)
```

Semaphore 기반 동시성 제어로 측정한 결과:

| 설정 | RPS | p99 | 에러율 | 판정 |
|------|-----|-----|--------|------|
| Semaphore=10 | 32.9 | 1.60s | 15.3% | 사용 불가 |
| Semaphore=30 | 76.1 | 1.10s | 4.4% | 개선 필요 |
| **Semaphore=50** | **118.0** | **1.23s** | **1.0%** | **최적** |
| Semaphore=80 | 156.2 | 894ms | 14.6% | Rate Limit 초과 |

> **Sustainable RPS = Semaphore / Avg Latency = 50 / 0.4s ≈ 125 RPS**

캐시 HIT 시 1,515 RPS. 캐시 MISS 시 230 RPS. 이 230 RPS가 Nexon API의 물리적 한계다. Fan-Out Explosion이 발생하면, 1000개의 동시 cold miss가 이 230 RPS 한계를 순식간에 초과한다.

## 해결: Global Admission Control

### 이미 구현되어 있었다

ADR-383(2026년 3월 28일)에서 설계된 `GlobalAdmissionControl`은 이미 코드베이스에 존재했다. 하지만 **비활성화** 상태였다.

```yaml
# application.yml
ratelimit:
  enabled: false  # ← 구현은 되어 있지만 꺼져 있음
```

코드를 열어보니 완벽한 구현체가 이미 있었다.

### 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                   GlobalAdmissionControl                     │
│                                                              │
│  HTTP Request → submitOrWait()                              │
│       │                                                      │
│       ├── Fast Path: Semaphore 즉시 획득 → 바로 실행         │
│       │                                                      │
│       └── Slow Path: ArrayBlockingQueue(1000) 대기          │
│               │                                              │
│               ├── Worker Pool(16)이 큐 소비                  │
│               │       │                                      │
│               │       ├── Semaphore(100) 획득 → 실행         │
│               │       └── 5초 타임아웃 → 503 + Retry-After  │
│               │                                              │
│               └── Queue Full → 즉시 거부 (Fast Reject)       │
│                                                              │
│  Early Rejection: Queue >80% AND CPU >5.0 → 선제 거부       │
└─────────────────────────────────────────────────────────────┘
```

핵심은 **HTTP 스레드를 절대 블로킹하지 않는 것**. 이전 설계에서는 HTTP 스레드가 semaphore.tryAcquire()에서 대기하면서 스레드 풀이 고갈되었다. 수정 후에는 HTTP 스레드는 큐에 넣고 즉시 반환하고, Worker 스레드가 큐를 소비한다.

```kotlin
// 핵심: HTTP 스레드는 Non-Blocking
fun <T> submitOrWait(key: String, task: Callable<T>): CompletableFuture<T> {
    val request = AdmissionRequest(key, task, future, System.nanoTime())

    // Fast path: 즉시 실행 가능하면 바로 실행
    if (tryAcquireImmediately(request)) return future

    // Slow path: 큐에 넣고 즉시 반환 (HTTP 스레드 블로킹 없음)
    val offered = admissionQueue.offer(request)

    if (!offered) {
        // Queue full → Fast Reject
        future.completeExceptionally(AdmissionRejectedException("Queue full"))
    }

    return future
}
```

### 설정값과 설계 의도

```kotlin
// GlobalAdmissionProperties
maxInFlight:     100   // 동시 실행 cold-path 제한 (cores × 12.5)
maxQueueSize:    1000  // 대기 큐 크기 (maxInFlight의 10배)
workerPoolSize:  16    // 큐 소비 Worker 수
queueTimeoutMs:  5000  // 대기 최대 5초, 초과 시 503
```

`maxInFlight = 100`의 근거:

```
공식: I/O-bound 동시성 = cores × 10~20
8 cores × 12.5 = 100

검증:
max_in_flight=100, avg_latency=0.5s → QPS_limit = 200 (안정)
Nexon API 한계 ~230 RPS와 정렬
```

초과 요청은 503 Service Unavailable + Retry-After 헤더로 응답한다. 사용자는 재시도하면 되고, 시스템은 죽지 않는다.

### Early Rejection

P0 수정사항. 큐가 꽉 찰 때까지 기다리지 않고, 시스템 부하 징후가 보이면 선제적으로 거부한다.

```kotlin
// Queue >80% AND CPU load >5.0 → 선제 거부
if (currentQueueDepth > maxQueueSize * 0.8 && cpuLoad > 5.0) {
    earlyRejectionQueueFullCounter.increment()
    earlyRejectionCpuHighCounter.increment()
    future.completeExceptionally(
        AdmissionRejectedException("System under heavy load")
    )
    return future
}
```

이것이 없으면 큐가 100% 찰 때까지 요청을 받다가, 한꺼번에 타임아웃 폭풍이 발생한다.

### DIP 적용

`module-web`이 `module-infra`를 직접 참조하지 않도록 Port/Adapter 패턴을 적용했다.

```
module-web → AdmissionPort (module-core 인터페이스)
module-app → AdmissionPortAdapter → GlobalAdmissionControl (module-infra)
```

## 트레이드오프

| 항목 | 이점 | 대가 |
|------|------|------|
| **CPU 포화 방지** | 시스템 전체 장애 예방 | 초과 요청 503 응답 |
| **Fast Reject** | HTTP 스레드 블로킹 없음 | 일부 요청 즉시 거부 |
| **Early Rejection** | 타임아웃 폭풍 예방 | Queue >80% 시 보수적 거부 |
| **Retry-After** | 클라이언트 재시도 가능 | 클라이언트 구현 필요 |
| **Worker Pool** | HTTP-Worker 분리 | Worker 스레드 16개 추가 |

## 활성화 전략 (ADR-383)

Admission Control은 구현되어 있지만 아직 **활성화되지 않았다**. ADR-383에서 3-phase 전략을 수립했다.

### Phase 1: Admission Control 활성화 (P0)

```
변경: application.yml에서 ratelimit.enabled: true
효과: 즉시 Fan-Out Explosion 방지
검증: 500 RPS에서 503 응답 비율 < 5%
```

설정 변경만으로 즉시 효과. 코드 수정 없음.

### Phase 2: CPU 최적화 (P1)

```
JSON 파싱: 200-300KB 전체 → 필요 필드만 (25-30% → 10-15%)
Gzip 압축: 캐시된 결과는 skip
확률 DP: 프로파일링 후 판단
```

### Phase 3: Changed-only Upsert (P1)

```
dirty tracking → 변경된 row만 upsert
Write 감소 30-50% 예상
```

### 모니터링

```yaml
# Prometheus Alert Rules
- alert: AdmissionRejectRateHigh
  expr: rate(admission_rejected_total[5m]) / rate(admission_total[5m]) > 0.2
  annotations:
    summary: "Admission Control 거부율 > 20% — max-in-flight 증설 검토"
```

핵심 메트릭:

| 메트릭 | 정상 | 경고 |
|--------|------|------|
| `admission_rejected_total` | < 5% | > 20% (임계값 조정) |
| `admission_in_flight` | 100 이하 | 100 지속 (한계 도달) |
| `cpu_usage_percent` | < 70% | > 80% (Phase 2 우선) |

## 배운 점

> **"보호막은 폭풍이 오기 전에 설치해야 한다."**

Admission Control은 장애가 발생한 후에 만드는 것이 아니다. 장애가 **발생할 수 있다는 분석**만으로도 미리 만들어야 한다.

이 장의 핵심 교훈:

1. **SingleFlight는 같은 키만 보호한다**. 서로 다른 키의 동시 요청은 별도의 보호가 필요하다.
2. **API의 물리적 한계는 피할 수 없다**. Nexon API 230 RPS. 이 한계를 넘으면 반드시 보호가 필요하다.
3. **구현과 활성화는 다르다**. 코드가 있어도 설정이 꺼져 있으면 없는 것과 같다.
4. **HTTP 스레드를 블로킹하면 안 된다**. Worker Pool 패턴으로 요청 처리와 HTTP 응답을 분리해야 한다.

---

> **이 시점의 RPS: ~7,347 (200k~300k rows, Fan-Out 보호 구현 완료, 활성화 대기)**
> **관련 이슈**: #617 (Admission Control), #623 (Fan-Out)
> **관련 ADR**: ADR-383 (Fan-Out 최적화 + Admission Control 활성화)

**다음 장**: [12장 — 에필로그: 97에서 7,347, 그리고 그 너머](./12_epilogue.md)
