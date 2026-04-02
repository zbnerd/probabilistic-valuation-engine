# 2장: 방패를 들다 — Resilience4j와 서킷 브레이커의 탄생

> ADR-052: Resilience4j 2.2.0 Circuit Breaker 채택
>
> "더 이상 외부 장애가 우리 시스템을 죽이지 못하게 하자."

---

## 결정의 배경

1장의 도미노 장애 이후, 우리는 서킷 브레이커 도입을 결정했다. 선택지는 두 가지였다.

**Hystrix** — Netflix의 전설. 하지만 2018년부터 유지보수 모드. Spring Cloud에서도 권장하지 않는다.

**Resilience4j** — 경량, 함수형, Java 8+ 함수형 인터페이스 지원. Spring Boot 3.x 호환. Micrometer 연동으로 Prometheus와 자동 연결.

선택은 자연스러웠다. **Resilience4j 2.2.0**.

---

## 네 개의 방패

서킷 브레이커는 하나가 아니었다. 시스템의 각 위험 지점마다 다른 설정이 필요했다.

### 방패 1: nexonApi — 외부 API 호출용

```yaml
resilience4j:
  circuitbreaker:
    instances:
      nexonApi:
        slidingWindowSize: 100       # ADR-052: 통계적 유의성 확보
        failureRateThreshold: 50%
        waitDurationInOpenState: 5m  # ADR-052: 외부 API 복구 시간 반영
        minimumNumberOfCalls: 10
```

100번의 호출 중 50번이 실패하면 서킷이 열린다. 5분 동안 모든 요청을 차단하고, 5분 후에 반열림 상태에서 테스트 요청을 보낸다.

초기에는 `minimumNumberOfCalls` 설정이 없었다. Red Agent가 장애대응 테스트에서 발견한 P1 버그다. **최소 호출 수가 없으면 첫 번째 실패만으로 서킷이 열린다.** 정상적인 간헐적 에러에 과도하게 반응하는 문제였다.

```yaml
# Red Agent P1 Fix
minimumNumberOfCalls: 10  # 최소 10번 호출 후 판단
```

Bulkhead도 함께 설정했다. 동시에 50개까지만 외부 API를 호출한다. 500ms 이상 대기하면 요청을 거부한다.

```yaml
resilience4j:
  bulkhead:
    instances:
      nexonApi:
        maxConcurrentCalls: 50
        maxWaitDuration: 500ms
```

### 방패 2: likeSyncDb — Like 동기화 DB 배치용

```yaml
likeSyncDb:
  slidingWindowSize: 5
  failureRateThreshold: 60%
  waitDurationInOpenState: 30s
  minimumNumberOfCalls: 3
  permittedNumberOfCallsInHalfOpenState: 2
```

Like 도메인은 DB 배치 업데이트를 수행한다. DB에 문제가 생기면 5번 중 3번이 실패했을 때 서킷이 열린다. 반열림 상태에서는 2개의 요청만 허용해 조심스럽게 테스트한다.

### 방패 3: postgresLock — PostgreSQL 분산 락용

```yaml
postgresLock:
  slidingWindowSize: 20
  failureRateThreshold: 60%
  waitDurationInOpenState: 30s
  minimumNumberOfCalls: 5
  recordExceptions:
    - java.util.concurrent.TimeoutException
```

락 경합은 정상적인 상황에서도 발생한다. 모든 락 타임아웃을 에러로 간주하면 안 된다. 그래서 `recordExceptions`에 TimeoutException만 지정했다.

### 방패 4: openAiApi — OpenAI API 호출용

```yaml
openAiApi:
  waitDurationInOpenState: 60s
```

LLM은 복구에 시간이 필요하다. 서킷이 열린 후 60초를 기다린다. 조급하게 재시도하면 복구 중인 서버에 더 큰 부하를 준다.

---

## Aspect 순서: 보이지 않는 중요한 결정

서킷 브레이커와 재시도는 둘 다 AOP로 구현된다. 순서가 중요하다.

```yaml
# Retry가 CircuitBreaker를 감싸도록 설정
Retry Aspect Order: 399
CircuitBreaker Aspect Order: 400
```

왜 Retry가 바깥에 있어야 하는가?

```
[Retry 바깥]
  └── [CircuitBreaker 안쪽]
        └── 실제 메서드 호출
```

1. Retry가 요청을 받는다
2. CircuitBreaker를 통해 메서드를 호출한다
3. 실패하면 CircuitBreaker가 기록한다
4. Retry가 재시도한다
5. CircuitBreaker가 다시 기록한다

만약 순서가 반대라면? CircuitBreaker가 Retry를 감싸게 되고, 재시도가 서킷 통계에 반영되지 않는다. 서킷이 열려야 할 때 열리지 않는 치명적인 버그가 된다.

---

## 첫 번째 실전: 323회의 트립

서킷 브레이커를 배포한 후, Nexon API 장애가 다시 발생했다.

이번에는 달랐다.

```
CircuitBreakerMetricsCollector 기록:

nexonApi 서킷 브레이커:
  상태: OPEN → HALF_OPEN → CLOSED (자동 복구)
  총 트립 횟수: 323회
  실패율: 52% (임계치 50% 초과로 트립)
  서비스 중단: 0건

나머지 서비스:
  캐시 히트율: 99.7% 유지
  사용자에게 미치는 영향: 최소화
```

**323회의 서킷 브레이커 트립.** Nexon API는 323번이나 문제를 일으켰다. 하지만 이번에는 서비스 중단이 0건이었다.

서킷 브레이커가 열리면 즉시 Fallback 응답을 반환한다. 캐시된 데이터가 있으면 캐시를, 없으면 "일시적으로 사용할 수 없습니다"를 보여준다. 사용자는 불편을 겪지만, 서비스 전체가 마비되지는 않는다.

---

## CircuitBreakerMetricsCollector: 방패의 상태를 보는 눈

서킷 브레이커의 상태를 모니터링하기 위해 `CircuitBreakerMetricsCollector`를 구현했다.

```kotlin
@Component
class CircuitBreakerMetricsCollector(
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
) : MetricsCollectorStrategy {

    override fun collect(): Map<String, Any> = buildMap {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach { cb ->
            val cbMetrics = cb.metrics  // 서킷 브레이커 메트릭
            val cbData = linkedMapOf<String, Any>(
                "state" to cb.state.name,
                "failure_rate" to formatDouble(cbMetrics.failureRate.toFloat()),
                "slow_call_rate" to formatDouble(cbMetrics.slowCallRate.toFloat()),
                "buffered_calls" to cbMetrics.numberOfBufferedCalls,
                "failed_calls" to cbMetrics.numberOfFailedCalls,
                "successful_calls" to cbMetrics.numberOfSuccessfulCalls,
                "not_permitted_calls" to cbMetrics.numberOfNotPermittedCalls,
            )
            this[name] = cbData
        }

        // 요약: 열린 서킷 수, 반열림 수, 전체 수
        this["summary_open_count"] = openCount
        this["summary_half_open_count"] = halfOpenCount
    }
}
```

이제 언제 어느 서킷이 열렸는지, 실패율이 얼마인지, 차단된 요청이 몇 개인지 실시간으로 알 수 있다.

Prometheus와 연동하면 Grafana 대시보드에서 서킷 상태를 시각화할 수 있다. 이전에는 장애 사실조차 몰랐다. 이제는 서킷 상태 변화를 초 단위로 추적한다.

---

## 장애대응 테스트: Scenario A/B/C

서킷 브레이커 도입 후, 세 가지 시나리오로 장애대응 테스트를 수행했다.

### Scenario A: Nexon API 완전 장애

```
테스트: Nexon API 100% 실패 유도
결과:
  서킷 브레이커: 2.5초 내 TRIPPED ✅
  Fallback 활성화: 72% 요청 정상 처리 (캐시 활용)
  데이터 유실: 없음
  전파 방지: 완료 ✅
```

### Scenario B: Nexon API 간헐적 장애

```
테스트: 30% 실패율 유도
결과:
  서킷 브레이커: 열리지 않음 (임계치 50% 미달)
  재시도: 2회 재시도로 95% 복구 ✅
  사용자 영향: 최소
```

### Scenario C: Nexon API 지연

```
테스트: 응답 시간 5초로 유도
결과:
  Slow Call Rate 임계치 초과 → 서킷 TRIPPED ✅
  타임아웃: 5초 설정으로 빠른 감지
  복구: 10초 후 HALF_OPEN → 정상 확인 → CLOSED
```

세 시나리오 모두 **서비스 중단 0건**. 1장의 도미노 장애는 재현되지 않았다.

---

## NexonDataCollector: 방패를 든 코드

서킷 브레이커 도입 후, NexonDataCollector는 이렇게 변했다.

```java
@Service
public class NexonDataCollector implements NexonDataCollectorPort {

    private final WebClient webClient;
    private final LogicExecutor executor;

    private Mono<NexonApiCharacterData> fetchFromNexonApi(String ocid) {
        return webClient
            .get()
            .uri("/character/basic?ocid={ocid}", ocid)
            .retrieve()
            .bodyToMono(NexonApiCharacterData.class)
            .timeout(Duration.ofSeconds(5))     // 5초 타임아웃
            .retryWhen(Retry.backoff(2, Duration.ofMillis(100))
                .filter(this::isRetryableError))  // 2회 재시도
            .onErrorMap(this::translateWebClientError);  // 예외 변환
    }
}
```

- **5초 타임아웃**: 이전의 무한 대기가 사라졌다. 5초 안에 응답하지 않으면 즉시 실패로 처리한다.
- **2회 재시도**: 일시적 에러는 재시도로 복구한다. Exponential Backoff로 100ms, 200ms 간격.
- **예외 변환**: WebClient의 기술적 예외를 도메인 예외로 변환한다. `ExternalServiceException("NexonAPI", ...)`.

---

## Jitter: 재시도 폭풍을 막는 나침반

재시도에 Jitter를 추가하는 것은 나중에 발견한 중요한 개선이었다.

```yaml
resilience4j:
  retry:
    instances:
      nexonApi:
        maxAttempts: 3
        waitDuration: 500ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
```

Jitter가 없으면 모든 클라이언트가 같은 시간에 재시도한다. "Retry Storm" — 실패한 100개의 요청이 정확히 500ms 후에 동시에 재시도하는 재앙. 이것은 장애대응 테스트 Scenario 09에서 발견되었다.

```
장애대응 테스트 결과 — Retry Storm (Jitter 도입 전)

동시 클라이언트: 10
총 시도 횟수: 24 (최대 가능: 30)
재시도 증폭: 2.4배 ✅ (3배 임계치 미만)
```

2.4배는 3배 미만이라 통과했지만, 안전 margin이 얇았다. Jitter를 추가한 후:

```
재시도 증폭 (Jitter 도입 후): 1.8배 ✅ (안전)
```

---

## 교훈

**1. 서킷 브레이커는 방패다. 공격을 막아주지만, 근본 원인을 해결하지는 않는다.**

Nexon API가 불안정한 건 여전하다. 하지만 서킷 브레이커가 있으면 그 불안정함이 전체 시스템으로 전파되지 않는다.

**2. 설정은 시나리오별로 달라야 한다.**

외부 API용, DB용, 락용, LLM용 — 각각 다른 특성을 가진다. 하나의 설정으로 모든 상황을 커버할 수 없다.

**3. Aspect 순서는 보이지 않지만 치명적이다.**

Retry와 CircuitBreaker의 순서가 뒤바뀌면 서킷 브레이커가 무력화된다. 이건 장애대응 테스트에서만 발견할 수 있는 유형의 버그다.

**4. 323회의 트립은 "많이 고장 났다"가 아니라 "323번 막아냈다"다.**

서킷 브레이커가 트립된 횟수는 방어 성공 횟수다. 이전에는 323번의 장애가 모두 전체 서비스 마비로 이어졌을 것이다.

---

> **다음 장:** [3장: 보이지 않는 적 — 커넥션 풀 고갈의 여정](03_connection_pool.md)
