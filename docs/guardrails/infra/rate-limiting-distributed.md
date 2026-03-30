---
id: GR-INFRA-004
category: infra
severity: high
keywords: [RateLimiting, Bucket4j, Redis, Distributed, FailClosed, DoS]
---

# Rate Limiting in Distributed Environment

## DON'T (안티패턴)

### 1. 인스턴스별 독립 Rate Limiting
```java
// Bad: 각 인스턴스가 독립적인 카운터 보유
private final ConcurrentHashMap<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

public boolean allowRequest(String ip) {
    AtomicInteger count = requestCounts.computeIfAbsent(ip, k -> new AtomicInteger(0));
    return count.incrementAndGet() <= MAX_REQUESTS;
}
```

**영향:**
- 2개 인스턴스 시 60 RPS → 실제 120 RPS (한도 초과)
- DoS 공격에 취약
- 인스턴스별 부하 불균형

### 2. Fail-Open 동작 (Redis 장애 시)
```java
// Bad: Redis 장애 시 요청 모두 허용
public boolean allowRequest(String ip) {
    try {
        return rateLimiter.tryAcquire();
    } catch (Exception e) {
        log.error("Rate limiter failed", e);
        return true;  // ← Redis 장애 시 차단 안 함 (Fail-Open)
    }
}
```

**영향:**
- Redis 장애 시 DoS 방어 무력화
- API 오버로드 가능성

### 3. IP 기반만 사용 (우회 가능)
```java
// Bad: IP만 사용하면 우회 가능
String key = "ratelimit:" + clientIp;  // IP만 사용
```

**영향:**
- 프록시/VPN 사용 시 우회 가능
- 다중 사용자 환경에서 공유 IP 문제

### 4. 분산 락 없는 Token Bucket
```java
// Bad: 로컬 Token Bucket
private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
```

## DO (베스트 프랙티스)

### 1. Bucket4j 분산 설정
```java
// Good: Redis 기반 분산 Rate Limiting
@Configuration
public class RateLimiterConfig {

    @Bean
    public Bucket4j bucket4j(RedissonClient redissonClient) {
        // Redisson 기반 프로キ시 매니저
        RedissonProxyManager<String> proxyManager = RedissonProxyManager.builder()
            .withClient(redissonClient)
            .build();

        // Bucket4j 설정
        return Bucket4j.builder()
            .addConfiguration(Bandwidth.classic(60,
                Refill.intervally(60, Duration.ofMinutes(1))))
            .build();
    }
}
```

### 2. 분산 Rate Limiter 구현
```java
// Good: Redis 기분 Distributed Rate Limiter
@Component
public class DistributedRateLimiter {

    private final RedissonClient redissonClient;
    private final MeterRegistry meterRegistry;

    public boolean allowRequest(String key, int limit, int periodSeconds) {
        String rateLimitKey = "ratelimit:" + key;

        RRateLimiter rateLimiter = redissonClient.getRateLimiter(rateLimitKey);

        // RateLimiter 설정 (존재하지 않을 때만)
        if (!rateLimiter.isExists()) {
            // 1초에 N회 요청 제한
            rateLimiter.trySetRate(RateType.OVERALL, limit, periodSeconds, RateIntervalUnit.SECONDS);
        }

        // 요청 시도
        boolean allowed = rateLimiter.tryAcquire(1);

        // 메트릭 기록
        if (allowed) {
            meterRegistry.counter("ratelimit.allowed", "key", key).increment();
        } else {
            meterRegistry.counter("ratelimit.denied", "key", key).increment();
        }

        return allowed;
    }
}
```

### 3. Fail-Closed 전략
```java
// Good: Redis 장애 시 차단 (Fail-Closed)
public boolean allowRequest(String key) {
    try {
        return rateLimiter.tryAcquire(1);
    } catch (Exception e) {
        log.error("[RateLimit] Redis unavailable, applying Fail-Closed policy", e);

        // Redis 장애 시 차단 (보안 우선)
        meterRegistry.counter("ratelimit.redis_unavailable").increment();
        return false;  // ← Fail-Closed
    }
}
```

### 4. 다중 차원 Rate Limiting
```java
// Good: IP + 사용자별 복합 제한
@Component
public class MultiDimensionRateLimiter {

    private final DistributedRateLimiter rateLimiter;

    public boolean allowRequest(String ip, String sessionId, String userId) {
        // 1. IP별 제한 (DoS 방어)
        boolean ipAllowed = rateLimiter.allowRequest(
            "ip:" + ip, 100, 60);

        if (!ipAllowed) {
            log.warn("[RateLimit] IP limit exceeded: {}", ip);
            return false;
        }

        // 2. 세션별 제한 (세션 하이재킹 방지)
        boolean sessionAllowed = rateLimiter.allowRequest(
            "session:" + sessionId, 50, 60);

        if (!sessionAllowed) {
            log.warn("[RateLimit] Session limit exceeded: {}", sessionId);
            return false;
        }

        // 3. 사용자별 제한 (무차별 대입 방지)
        if (userId != null) {
            boolean userAllowed = rateLimiter.allowRequest(
                "user:" + userId, 30, 60);

            if (!userAllowed) {
                log.warn("[RateLimit] User limit exceeded: {}", userId);
                return false;
            }
        }

        return true;
    }
}
```

### 5. Spring Security Filter 통합
```java
// Good: Filter에서 Rate Limiting 적용
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final MultiDimensionRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String ip = getClientIp(request);
        String sessionId = getSessionId(request);
        String userId = getUserId(request);

        // Rate Limiting 검증
        boolean allowed = rateLimiter.allowRequest(ip, sessionId, userId);

        if (!allowed) {
            meterRegistry.counter("http.requests.rejected",
                "reason", "rate_limit").increment();

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
```

### 6. Sliding Window Log (고급)
```java
// Good: 더 정확한 Sliding Window
public class SlidingWindowRateLimiter {

    private final RedissonClient redissonClient;

    public boolean allowRequest(String key, int maxRequests, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowStart = now - (windowSeconds * 1000L);

        String redisKey = "ratelimit:sliding:" + key;

        RScoredSortedSet<Long> sortedSet = redissonClient.getScoredSortedSet(redisKey);

        // 현재 윈도우 내 요청만 유지
        sortedSet.removeRange(0, true, windowStart, true);

        // 현재 요청 추가
        sortedSet.add(now, now);
        sortedSet.expire(windowSeconds + 1, TimeUnit.SECONDS);

        // 현재 윈도우 내 요청 수 확인
        long count = sortedSet.size(0, true, now, true);

        return count <= maxRequests;
    }
}
```

## Monitoring & Alerts

```prometheus
# Rate Limit 차단율
ALERT RateLimitDenialRateHigh
  IF rate(ratelimit_denied_total[5m]) / rate(ratelimit_allowed_total[5m]) > 0.1
  SEVERITY warning

  ANNOTATIONS {
    summary = "High rate limit denial rate",
    description = "Possible DoS attack or limits too strict"
  }

# Redis 장애 시 Fail-Closed
ALERT RateLimitRedisUnavailable
  IF rate(ratelimit_redis_unavailable_total[1m]) > 0
  SEVERITY critical

  ANNOTATIONS {
    summary = "Rate limiter Redis unavailable",
    description = "Fail-Closed policy active, requests blocked"
  }

# IP별 Rate Limit 초과
ALERT RateLimitIPExceeded
  IF rate(ratelimit_denied_total{dimension="ip"}[5m]) > 10
  SEVERITY warning

  ANNOTATIONS {
    summary = "IP exceeding rate limit",
    description = "Possible bot or attack"
  }
```

## Verification Commands

```bash
# 1. Rate Limit 테스트
for i in {1..150}; do
  curl -w "%{http_code}\n" -o /dev/null -s http://localhost:8080/api/v2/characters/test
done | grep 429 | wc -l
# Expected: 90+ requests blocked (after 60 allowed)

# 2. IP 우회 테스트
# IP1에서 60회 요청
for i in {1..60}; do
  curl -H "X-Forwarded-For: 1.1.1.1" http://localhost:8080/api/v2/characters/test
done

# IP2에서 60회 요청 (별도 카운터)
for i in {1..60}; do
  curl -H "X-Forwarded-For: 2.2.2.2" http://localhost:8080/api/v2/characters/test
done
# Expected: Both IPs get 60 requests each (not shared)

# 3. Redis Key 확인
redis-cli --scan --pattern "ratelimit:*" | head -10

# 4. Rate Limiter 상태 확인
redis-cli TSGET ratelimit:ip:127.0.0.1

# 5. 메트릭 확인
curl http://localhost:8080/actuator/metrics/ratelimit.allowed
curl http://localhost:8080/actuator/metrics/ratelimit.denied
```

## Anti-Patterns

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **인스턴스별 카운터** | Scale-out 시 제한 증가 | Redis 분산 카운터 |
| **Fail-Open** | Redis 장애 시 DoS 가능 | Fail-Closed 정책 |
| **IP만 사용** | 프록시로 우회 가능 | IP + Session + User 복합 |
| **로컬 Token Bucket** | 인스턴스 간 공유 안 됨 | Redisson RRateLimiter |

## Bucket4j vs Redisson

| 특성 | Bucket4j | Redisson |
|------|---------|----------|
| **구현** | Token Bucket 알고리즘 | RRateLimiter 기본 제공 |
| **분산 지원** | RedissonProxyManager | 기본 분산 |
| **복잡도** | 설정 복잡 | 간단한 API |
| **융통성** | 다양한 알고리즘 | 기본 Rate Limiter |
| **성능** | 약간 빠름 | 충분히 빠름 |

## 출처
- [docs/05_Reports/05_09_Scale_Out/scale-out-blockers-analysis.md](../../../05_Reports/05_09_Scale_Out/scale-out-blockers-analysis.md) P1-1
- [Bucket4j Documentation](https://bucket4j.com/)
- [Redisson RateLimiter](https://redisson.org)
