---
id: GR-RESILIENCE-006
category: backend/resilience
severity: warning
keywords: [Warmup, ColdCache, PopularCharacter, Redis, ZSET, ZINCRBY, ZREVRANGE, Scheduler]
languages: [java, kotlin]
---

# Auto Warmup Strategy Guardrail

## 개요

V4 API의 **Cold Cache 문제**를 해결하기 위해 자동 웜업 시스템을 필수로 적용합니다. 전날 인기 캐릭터 TOP N을 추적하여 서버 시작 시 또는 매일 새벽에 자동으로 캐시를 채워 RPS를 3배 향상시킵니다.

> **설계 근거:** Load test N23에서 Cold Cache 시 RPS가 95로 저하되었으나, Warmup 후 310으로 3.3배 개선되었습니다. (95 → 310 RPS)

## DON'T (안티패턴)

### 1. Eager Load 모든 데이터 (과도한 시작 시간)

```java
// Bad - 모든 캐릭터를 시작 시 로드
@PostConstruct
public void loadAllCharacters() {
    List<String> allCharacters = characterRepository.findAll();
    allCharacters.forEach(ign -> {
        V4ApiCache.forceWarmup(ign);  // 수천 건의 API 호출
    });
}

// 문제:
// - 시작 시간 >30분
// - 넥슨 API 과부하
// - 대부분의 데이터는 사용되지 않음
```

**위험성:**
- 서버 시작 시간 과도하게 증가
- 외부 API Rate Limiting
- 불필요한 리소스 낭비

### 2. 분산 락 없는 스케줄러 (중복 웜업)

```java
// Bad - 분산 락 없는 스케줄러
@Scheduled(cron = "0 0 5 * * *")
public void dailyWarmup() {
    // 분산 락 없음
    List<String> topCharacters = tracker.getTopCharacters(100);
    topCharacters.forEach(ign -> v4Api.warmup(ign));
}

// 문제: 다중 인스턴스 배포 시 중복 웜업
// - 3대 인스턴스 = 3배 중복 API 호출
// - 넥슨 API 과부하
// - 캐시 불일치 가능성
```

**위험성:**
- 다중 인스턴스에서 중복 실행
- API Rate Limiting 위반
- 캐시 불일치

### 3. 추적 없는 웜업 (어떤 캐릭터를 웜업할지 모름)

```java
// Bad - 인기도 추적 없이 랜덤 웜업
@Scheduled(cron = "0 0 5 * * *")
public void randomWarmup() {
    // 인기도 추적 없음
    List<String> randomCharacters = characterRepository.findRandom(100);
    randomCharacters.forEach(ign -> v4Api.warmup(ign));
}

// 문제: 실제 인기 캐릭터와 불일치
// - 웜업한 캐릭터는 요청 없음
// - 실제 인기 캐릭터는 Cold Cache 상태
```

**위험성:**
- 웜업 효과 없음
- 리소스 낭비
- Cold Cache 문제 지속

### 4. ZINCRBY 사용 없는 추적 (O(N) 복잡도)

```java
// Bad - String 값을 사용하여 추적
public void recordAccess(String ign) {
    String count = redis.get("popular:" + ign);
    redis.set("popular:" + ign, String.valueOf(Integer.parseInt(count) + 1));
}

// 문제: Top N 조회 불가능
// - 모든 키를 스캔해야 함 (O(N))
// - 메모리 낭비
```

**위험성:**
- Top N 조회 불가
- O(N) 스캔으로 성능 저하
- 메모리 비효율

## DO (베스트 프랙티스)

### 1. Redis Sorted Set (ZINCRBY)로 추적

```java
// Good - ZINCRBY로 점수 증가 (O(log N))
@Component
@RequiredArgsConstructor
public class PopularCharacterTracker {

    private final RedissonClient redissonClient;

    private static final String KEY_PREFIX = "popular:characters:";

    // 호출 기록 (Fire-and-Forget)
    public void recordAccess(String ign) {
        LocalDate today = LocalDate.now();
        String key = KEY_PREFIX + today;

        // ZINCRBY: O(log N)
        redissonClient.getScoredSortedSet(key)
            .addScore(ign, 1);

        // TTL 48시간 (전날 데이터 참조용)
        redissonClient.getBucket(key).expire(48, TimeUnit.HOURS);
    }

    // 인기 캐릭터 조회 (상위 N개)
    public List<String> getTopCharacters(LocalDate date, int limit) {
        String key = KEY_PREFIX + date;

        // ZREVRANGE: O(log N + M)
        Collection<String> top = redissonClient.getScoredSortedSet(key)
            .valueRangeReversed(0, limit - 1);

        return new ArrayList<>(top);
    }

    // 전날 인기 캐릭터 조회 (웜업용)
    public List<String> getYesterdayTopCharacters(int limit) {
        return getTopCharacters(LocalDate.now().minusDays(1), limit);
    }
}
```

### 2. 분산 락을 사용한 스케줄러

```java
// Good - 분산 락으로 중복 실행 방지
@Component
@ConditionalOnProperty(name = "scheduler.warmup.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PopularCharacterWarmupScheduler {

    private final PopularCharacterTracker tracker;
    private final V4ApiService v4Api;
    // ⚠️ DEPRECATED: Use PostgresAdvisoryLockStrategy instead (ADR-022)
    private final RedisDistributedLockStrategy lockStrategy;
    private final LogicExecutor executor;

    private static final String WARMUP_LOCK_KEY = "warmup:lock:daily";

    // 매일 새벽 5시 웜업
    @Scheduled(cron = "0 0 5 * * *")
    public void dailyWarmup() {
        executor.executeVoid(
            () -> {
                // 분산 락 획득 시에만 실행
                lockStrategy.executeWithLock(
                    WARMUP_LOCK_KEY,
                    () -> doWarmup("daily")
                );
            },
            TaskContext.of("WarmupScheduler", "DailyWarmup")
        );
    }

    // 서버 시작 후 30초 뒤 초기 웜업
    @Scheduled(initialDelay = 30000, fixedDelay = Long.MAX_VALUE)
    public void initialWarmup() {
        executor.executeVoid(
            () -> {
                lockStrategy.executeWithLock(
                    WARMUP_LOCK_KEY + ":initial",
                    () -> doWarmup("initial")
                );
            },
            TaskContext.of("WarmupScheduler", "InitialWarmup")
        );
    }

    private void doWarmup(String type) {
        log.info("Starting warmup: type={}", type);

        List<String> topCharacters = tracker.getYesterdayTopCharacters(
            warmupProperties.getTopCount()  // prod: 100
        );

        int successCount = 0;
        int failCount = 0;

        for (String ign : topCharacters) {
            try {
                // force=0: 캐시 미스 시에만 API 호출
                v4Api.getCharacter(ign, 0);
                successCount++;

                // 요청 간 지연 (Thundering Herd 방지)
                Thread.sleep(warmupProperties.getDelayBetweenMs());
            } catch (Exception e) {
                failCount++;
                log.warn("Warmup failed: ign={}, error={}", ign, e.getMessage());
            }
        }

        log.info("Warmup completed: type={}, success={}, fail={}",
            type, successCount, failCount);
    }
}
```

### 3. Fire-and-Forget 패턴 (API 지연 없음)

```java
// Good - API 요청 처리 흐름에서 비동기 추적
@RestController
@RequiredArgsConstructor
public class V4CharacterController {

    private final V4ApiService v4Api;
    private final PopularCharacterTracker tracker;
    private final ExecutorService executor;

    @GetMapping("/api/v4/character/{ign}")
    public ResponseEntity<V4CharacterResponse> getCharacter(@PathVariable String ign) {
        // 1. V4 API 호출 (동기)
        V4CharacterResponse response = v4Api.getCharacter(ign, 0);

        // 2. 인기도 추적 (비동기 Fire-and-Forget)
        executor.submit(() -> {
            try {
                tracker.recordAccess(ign);  // API 지연 없음
            } catch (Exception e) {
                // 추적 실패는 무시 (Fire-and-Forget)
                log.debug("Failed to record access: ign={}", ign);
            }
        });

        return ResponseEntity.ok(response);
    }
}
```

### 4. TTL 설정으로 메모리 관리

```java
// Good - ZSET TTL 48시간 설정
public void recordAccess(String ign) {
    LocalDate today = LocalDate.now();
    String key = KEY_PREFIX + today;

    RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);
    zset.addScore(ign, 1);

    // 핵심: 48시간 후 자동 삭제
    zset.expire(48, TimeUnit.HOURS);
}

// 메모리 사용량 추정
// - KEY: popular:characters:2026-02-25
// - Member: userIgn (avg 20 bytes)
// - Score: Double (8 bytes)
// - 10,000 캐릭터 × 28 bytes ≈ 280KB/day
// - 2일 보관 × 280KB ≈ 560KB (무시할 수 있는 수준)
```

### 5. application.yml 설정

```yaml
# Good - 프로필별 설정
scheduler:
  warmup:
    enabled: false           # local: 비활성화
    top-count: 50            # 웜업할 상위 캐릭터 수
    delay-between-ms: 100    # 요청 간 지연 (ms)
---
# application-prod.yml
scheduler:
  warmup:
    enabled: true            # prod: 활성화
    top-count: 100           # 상위 100개 웜업
    delay-between-ms: 50     # 빠른 웜업
```

### 6. 메트릭 수집

```java
// Good - 웜업 메트릭 수집
@Component
public class WarmupMetrics {

    private final MeterRegistry meterRegistry;

    public void recordWarmupExecution(String type, int success, int fail) {
        meterRegistry.counter("warmup.execution",
            "type", type,
            "status", "success"
        ).increment(success);

        meterRegistry.counter("warmup.execution",
            "type", type,
            "status", "fail"
        ).increment(fail);
    }

    public void recordWarmupDuration(String type, long durationMs) {
        meterRegistry.timer("warmup.duration",
            "type", type
        ).record(durationMs, TimeUnit.MILLISECONDS);
    }
}
```

### 7. Cold vs Warm 비교 테스트

```java
// Good - Cold vs Warm 성능 비교
@Test
@DisplayName("Cold Cache vs Warm Cache 성능 비교")
void compareColdVsWarmCache() {
    String ign = "popularCharacter";

    // Cold Cache 테스트
    redis.flushAll();
    long coldStart = System.nanoTime();
    v4Api.getCharacter(ign, 0);
    long coldDuration = System.nanoTime() - coldStart;

    // Warm Cache 테스트
    long warmStart = System.nanoTime();
    v4Api.getCharacter(ign, 0);
    long warmDuration = System.nanoTime() - warmStart;

    log.info("Cold: {}ms, Warm: {}ms, Improvement: {}x",
        coldDuration / 1_000_000,
        warmDuration / 1_000_000,
        (double) coldDuration / warmDuration
    );

    // Cold Cache는 Warm Cache보다 2배 이상 느림
    assertThat(coldDuration).isGreaterThan(warmDuration * 2);
}
```

## Redis 데이터 구조

```
Key:    popular:characters:{yyyy-MM-dd}
Type:   Sorted Set (ZSET)
Score:  호출 횟수
Member: userIgn (캐릭터 닉네임)
TTL:    48시간 (전날 데이터 참조용)

Redis 명령어:
- ZINCRBY popular:characters:2026-02-25 1 "userIgn"  # 호출 횟수 증가 (O(log N))
- ZREVRANGE popular:characters:2026-02-25 0 99       # 상위 100개 조회 (O(log N + M))
- EXPIRE popular:characters:2026-02-25 172800        # 48시간 TTL
```

## Stateless 설계

| 컴포넌트 | Stateless 보장 방법 |
|---------|---------------------|
| PopularCharacterTracker | Redis Sorted Set (모든 인스턴스 공유) |
| WarmupScheduler | 분산 락 (단일 인스턴스만 실행) |
| 호출 기록 | Fire-and-Forget (API 지연 없음) |

## Thundering Herd 방지

```java
// 요청 간 지연으로 동시 요청 분산
for (String ign : topCharacters) {
    try {
        v4Api.getCharacter(ign, 0);
        successCount++;

        // 핵심: 요청 간 지연 (50-100ms)
        Thread.sleep(warmupProperties.getDelayBetweenMs());
    } catch (Exception e) {
        failCount++;
    }
}

// 100개 웜업 × 50ms = 5초 소요
// 넥슨 API Rate Limiting 준수
```

## 출처

### 문서
- `docs/03_Technical_Guides/auto-warmup.md` - Auto Warmup 전략

### ADR
- Issue #275: Auto Warmup 기능 구현

### 코드 (Evidence)
- `src/main/kotlin/maple/expectation/service/v4/warmup/PopularCharacterTracker.java`
- `src/main/kotlin/maple/expectation/scheduler/PopularCharacterWarmupScheduler.java`

### 테스트
- `docs/05_Reports/Cost_Performance/N23_V4_API_RESULTS.md` - Cold vs Warm 성능 비교

## 검증 명령어

```bash
# 웜업 스케줄러 확인
find src/main/kotlin -name "*WarmupScheduler.java"

# 웜업 설정 확인
grep -A 5 "scheduler.warmup" src/main/resources/application.yml

# ZSET 패턴 확인
grep -r "popular:characters" src/main/kotlin --include="*.java"

# Warmup 메트릭 확인
curl -s http://localhost:8080/actuator/metrics/warmup.execution | jq

# Redis ZSET 확인
redis-cli ZREVRANGE "popular:characters:$(date +%Y-%m-%d)" 0 9 WITHSCORES
```
