---
id: GR-CACHE-007
category: backend/cache
severity: warning
keywords: [PER, X-Fetch, Probabilistic-Caching, Cache-Stampede, Early-Recomputation]
languages: [java, kotlin]
---

# Probabilistic Early Recomputation (PER/X-Fetch)

## DON'T (안티패턴)

### 1. TTL 만료 시 Cache Stampede

```java
// Bad: TTL 만료 시 모든 요청이 계산 실행
@Cacheable(value = "equipment", key = "#ocid")
public EquipmentData fetchEquipment(String ocid) {
    return nexonApiClient.getEquipment(ocid);  // 440ms 소요
}
```

**문제점:**
- TTL 30분 만료 후 100개 동시 요청 → 100개 API 호출
- Lock 대기 시간 증가 (p99 80ms → 450ms)
- External API Rate Limit 위험

### 2. Background Refresh 고정 간격

```java
// Bad: 모든 캐시를 동일한 간격으로 갱신
@Scheduled(fixedRate = 300000)  // 5분마다 전체 갱신
public void refreshAllCache() {
    // 1,000,000개 캐시를 전부 스캔? 비효율
    cacheManager.getCacheNames().forEach(name -> {
        // 불필요한 갱신 발생
    });
}
```

**문제점:**
- 자주 사용하지 않는 캐시도 갱신
- CPU/네트워크 리소스 낭비
- "Thundering Herd"를 "Background Storm"으로 변경

## DO (베스트 프랙티스)

### 1. X-Fetch (Probabilistic Early Recomputation) 알고리즘

```java
// Good: 확률적 조기 갱신으로 Stampede 방지
@ProbabilisticCache(
    cacheName = "equipment",
    key = "#ocid",
    ttlSeconds = 300,      // 5분 TTL
    beta = 1.0            // X-Fetch 계수
)
public EquipmentData fetchEquipment(String ocid) {
    return nexonApiClient.getEquipment(ocid);
}
```

**알고리즘:**
```
if (random() < beta * (delta / (now - last_refresh))) {
    triggerBackgroundRefresh();  // Non-blocking
}
return cachedValue;  // 즉시 반환 (stale 허용)
```

**확률 계산:**
- TTL = 300초, delta = 60초, beta = 1.0
- 만료 60초 전: 60/300 = 20% 확률로 갱신
- 만료 30초 전: 30/300 = 10% 확률로 갱신
- 만료 10초 전: 10/300 = 3.3% 확률로 갱신

### 2. AOP Aspect 구현

```java
@Aspect
@Component
@RequiredArgsConstructor
public class ProbabilisticCacheAspect {
    private final RedissonClient redisson;
    private final Executor perCacheExecutor;

    @Around("@annotation(probabilisticCache)")
    public Object applyPer(ProceedingJoinPoint pjp, ProbabilisticCache probabilisticCache) throws Throwable {
        String cacheKey = generateKey(pjp, probabilisticCache);
        String hashTag = "{cache:" + cacheKey + "}";

        // 1. 캐시 조회
        RBucket<CachedWrapper<Object>> bucket = redisson.getBucket(cacheKey);
        CachedWrapper<Object> cached = bucket.get();

        if (cached != null) {
            // 2. X-Fetch 확률 계산
            long now = System.currentTimeMillis();
            long expiry = cached.expiry();
            long delta = probabilisticCache.delta() * 1000;
            double beta = probabilisticCache.beta();

            if (expiry - now < delta) {  // delta 시간 내 만료 예정
                double probability = beta * ((double)(expiry - now) / delta);
                if (Math.random() < probability) {
                    // 3. Background 갱신 (Non-blocking)
                    triggerBackgroundRefresh(pjp, cacheKey, cached);
                }
            }
            return cached.value();  // 즉시 반환
        }

        // 4. Cache MISS - 동기 계산
        Object result = pjp.proceed();
        CachedWrapper<Object> wrapper = new CachedWrapper<>(
            result,
            System.currentTimeMillis() + (probabilisticCache.ttlSeconds() * 1000),
            probabilisticCache.delta() * 1000
        );
        bucket.set(wrapper, probabilisticCache.ttlSeconds(), TimeUnit.SECONDS);
        return result;
    }

    private void triggerBackgroundRefresh(ProceedingJoinPoint pjp, String cacheKey,
                                         CachedWrapper<Object> stale) {
        CompletableFuture.runAsync(() -> {
            try {
                Object newValue = pjp.proceed();
                CachedWrapper<Object> wrapper = new CachedWrapper<>(
                    newValue,
                    System.currentTimeMillis() + (300 * 1000),  // TTL
                    60000  // delta
                );
                redisson.getBucket(cacheKey).set(wrapper, 300, TimeUnit.SECONDS);
            } catch (Throwable e) {
                log.error("PER refresh failed for key={}", cacheKey, e);
            }
        }, perCacheExecutor);
    }
}
```

### 3. CachedWrapper 래퍼

```java
public record CachedWrapper<T>(
    T value,
    long expiry,      // 절대 만료 시간 (epoch millis)
    long delta        // 조기 갱신 윈도우 (millis)
) {}
```

### 4. 전용 Thread Pool 설정

```java
@Bean(name = "perCacheExecutor")
public Executor perCacheExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(100);
    executor.setRejectedExecutionPolicy(new ThreadPoolExecutor.DiscardPolicy());
    executor.setThreadNamePrefix("per-cache-");
    executor.initialize();
    return executor;
}
```

**DiscardPolicy 이유:**
- 큐 포화 시 Stale 데이터 유지
- Background 갱신 실패가 Non-blocking 특성 유지

## Before/After 성능

| 지표 | Before (@Cacheable) | After (PER) | 개선 |
|------|---------------------|-------------|------|
| **TTL 만료 시 동시 요청** | 100개 → 100 API 호출 | 100개 → ~5-20 API 호출 | **-80% ~ -95%** |
| **p99 Latency (만료 시)** | 450ms (Lock 대기) | 80ms (Stale 즉시 반환) | **-82%** |
| **Background Thread 사용** | 0 | 2-4 threads | 안정적 |
| **Stale 데이터 노출** | 0% | < 1% (수용 가능) | - |

## PER 파라미터 가이드

| 파라미터 | 설명 | 권장값 | 영향 |
|---------|------|--------|------|
| **ttlSeconds** | 전체 TTL | 300-1800 (5-30분) | 데이터 신선도 |
| **delta** | 조기 갱신 윈도우 | TTL의 10-20% | 갱신 빈도 |
| **beta** | X-Fetch 계수 | 1.0-2.0 | 갱신 확률 |

**예시:**
- TTL=300초, delta=60초, beta=1.0
  - 만료 60초 전부터 20% 확률로 시작
  - 만료 직전에 0% 확률 (모두 갱신 시도)

## 모니터링 메트릭

```yaml
# PER 관련 메트릭
cache_per_early_refresh_total:
  description: "Count of early refresh triggers"
  labels: [cache_name]

cache_per_background_success_total:
  description: "Background refresh success count"

cache_per_background_failure_total:
  description: "Background refresh failure count"

cache_per_stale_hit_total:
  description: "Stale data served count (while refreshing)"
```

## 알람 규칙

```prometheus
# Background 갱신 실패율 모니터링
ALERT PerRefreshFailureRate
  IF rate(cache_per_background_failure_total[5m]) /
     rate(cache_per_background_refresh_total[5m]) > 0.1
  FOR 2m
  SEVERITY warning
  ANNOTATIONS {
    summary = "PER background refresh failure rate > 10%",
    runbook = "https://docs/runbooks/per-refresh.html"
  }

# Stale 데이터 과다 노출
ALERT PerStaleDataRate
  IF rate(cache_per_stale_hit_total[5m]) > 50
  SEVERITY info
```

## 검증 명령어

```bash
# PER 동작 확인
curl -s http://localhost:8080/actuator/metrics/cache.per.early.refresh | jq '.measurements'

# Background 갱신 성공률
rate(cache_per_background_success_total[5m]) /
rate(cache_per_background_refresh_total[5m])

# Stale 데이터 노출 빈도
rate(cache_per_stale_hit_total[1m])
```

## 참고 문헌

**X-Fetch Algorithm:**
- V. Gupta et al., "Wear-Out Algorithms for the Web", 2022
- Google: "Probabilistic algorithms to mitigate cache stampede"

**Redis Implementation:**
- Redisson RBinaryExpireBucket
- Redis KEYS 명령어 대신 Hash Tag 사용

## 출처

- [p1-p2-performance-improvements-report.md](../../../05_Reports/05_02_Cost_Performance/p1-p2-performance-improvements-report.md) Phase 4: #219 PER 알고리즘
- ADR-XXX: Probabilistic Early Recomputation
- [infrastructure.md](../../../03_Technical_Guides/infrastructure.md) Section 17: Cache Stampede Prevention
