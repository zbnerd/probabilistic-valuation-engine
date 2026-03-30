<!-- 
<-- DEPRECATED --> This document references Redis/Redisson infrastructure completely removed. See ADR-022 (Redis removal), ADR-024 (MySQL removal). Redis replaced by PostgreSQL (advisory locks, UNLOGGED tables, NOTIFY/LISTEN).
 -->

---
id: GR-RESILIENCE-008
category: backend/resilience
severity: warning
keywords: [Redis, ZSET, TTL, MemoryLeak, SortedSet, Expire, AutoWarmup]
languages: [java, kotlin]
---

# Redis Sorted Set TTL Management Guardrail

## 개요

Redis Sorted Set(ZSET)을 사용하는 **인기 캐릭터 추적 시스템**에서 무한정 메모리가 증가하는 것을 방지하기 위해 **TTL(Time To Live) 설정**을 필수로 적용합니다. 48시간 TTL로 전날 데이터 참조와 메모리 관리를 동시에 달성합니다.

> **설계 근거:** ZSET에 TTL을 설정하지 않으면 매일 새로운 키가 생성되어 메모리가 무한히 증가합니다. 48시간 TTL로 당일+전날 데이터만 유지하여 메모리 사용량을 상수 수준으로 제한합니다.

## DON'T (안티패턴)

### 1. TTL 미설정 (메모리 누수)

```java
// Bad - TTL 미설정
public void recordAccess(String ign) {
    LocalDate today = LocalDate.now();
    String key = "popular:characters:" + today;

    RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);
    zset.addScore(ign, 1);
    // TTL 설정 없음 → 메모리 누수
}

// 문제:
// - Day 1:  "popular:characters:2026-02-01" 생성 (280KB)
// - Day 2:  "popular:characters:2026-02-02" 생성 (280KB)
// - Day 365: "popular:characters:2026-02-02" ~ "2026-02-365" 존재 (102MB)
// - 1년 후: 365개 키 × 280KB ≈ 100MB 메모리 누수
```

**위험성:**
- 메모리 사용량 무한 증가
- Redis OOM (Out Of Memory) 가능성
- 레거시 데이터로 메모리 낭비

### 2. 짧은 TTL (전날 데이터 참조 불가)

```java
// Bad - 1시간 TTL
public void recordAccess(String ign) {
    String key = "popular:characters:" + LocalDate.now();
    RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);
    zset.addScore(ign, 1);
    zset.expire(1, TimeUnit.HOURS);  // 너무 짧음
}

// 문제: 새벽 5시 웜업 시 전날 데이터가 이미 삭제됨
// - 00:00 ~ 04:59: ZKEY 생성 및 점수 누적
// - 01:00: TTL 만료로 삭제
// - 05:00: 웜업 시도 → 전날 데이터 없음
```

**위험성:**
- 전날 인기 캐릭터 추적 불가
- 웜업 기능 작동 안 함
- Cold Cache 문제 지속

### 3. 너무 긴 TTL (메모리 낭비)

```java
// Bad - 30일 TTL
public void recordAccess(String ign) {
    String key = "popular:characters:" + LocalDate.now();
    RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);
    zset.addScore(ign, 1);
    zset.expire(30, TimeUnit.DAYS);  // 너무 김
}

// 문제: 30일분 데이터 보관으로 메모리 낭비
// - 30일 × 280KB ≈ 8.4MB 불필요한 메모리 사용
// - 웜업은 전날 데이터만 필요함
```

**위험성:**
- 불필요한 메모리 사용
- 비용 증가
- Redis 성능 저하

## DO (베스트 프랙티스)

### 1. 48시간 TTL 설정 (당일+전날)

```java
// Good - 48시간 TTL
@Component
@RequiredArgsConstructor
public class PopularCharacterTracker {

    private final RedissonClient redissonClient;

    private static final String KEY_PREFIX = "popular:characters:";
    private static final long TTL_HOURS = 48;

    public void recordAccess(String ign) {
        LocalDate today = LocalDate.now();
        String key = KEY_PREFIX + today;

        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);

        // ZINCRBY: 점수 증가
        zset.addScore(ign, 1);

        // 핵심: 48시간 TTL 설정
        zset.expire(TTL_HOURS, TimeUnit.HOURS);
    }

    public List<String> getYesterdayTopCharacters(int limit) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String key = KEY_PREFIX + yesterday;

        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);

        // ZREVRANGE: 상위 N개 조회
        Collection<String> top = zset.valueRangeReversed(0, limit - 1);

        return new ArrayList<>(top);
    }
}
```

### 2. TTL 설정 시점 명확화

```java
// Good - 첫写入 시 TTL 설정
public void recordAccess(String ign) {
    String key = getKey();
    RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);

    // 첫写入 시 TTL 설정 (이후 갱신되지 않음)
    if (!zset.isExists()) {
        zset.expire(48, TimeUnit.HOURS);
    }

    zset.addScore(ign, 1);
}

// 또는 매번 TTL 갱신
public void recordAccess(String ign) {
    String key = getKey();
    RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);

    zset.addScore(ign, 1);

    // 핵심: 매번 TTL 갱신 (확실한 보장)
    zset.expire(48, TimeUnit.HOURS);
}
```

### 3. 메모리 사용량 모니터링

```java
// Good - ZSET 메모리 모니터링
@Component
public class RedisMemoryMonitor {

    private final RedissonClient redissonClient;
    private final MeterRegistry meterRegistry;

    @Scheduled(cron = "0 */10 * * * *")  // 10분마다
    public void monitorZsetMemory() {
        LocalDate today = LocalDate.now();
        String key = "popular:characters:" + today;

        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);

        if (zset.isExists()) {
            long size = zset.size();
            long memoryUsage = estimateMemoryUsage(size);

            meterRegistry.gauge("redis.zset.memory.bytes",
                Tags.of("key", key),
                memoryUsage
            );

            log.info("ZSET memory: key={}, size={}, memory={}KB",
                key, size, memoryUsage / 1024);
        }
    }

    private long estimateMemoryUsage(long size) {
        // Member: avg 20 bytes, Score: 8 bytes
        return size * 28;
    }
}
```

### 4. TTL 만료 전 경고

```java
// Good - TTL 만료 전 경고
@Component
public class TtlWarningService {

    private final RedissonClient redissonClient;

    @Scheduled(cron = "0 0 */6 * * *")  // 6시간마다
    public void checkTtlExpiry() {
        LocalDate today = LocalDate.now();
        String key = "popular:characters:" + today;

        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);

        if (zset.isExists()) {
            long remainTimeToLive = zset.remainTimeToLive();
            long remainHours = remainTimeToLive / (1000 * 60 * 60);

            if (remainHours < 12) {
                log.warn("ZSET TTL expiring soon: key={}, remainTTL={}hours",
                    key, remainHours);
            }
        }
    }
}
```

### 5. Redis CLI로 TTL 확인

```bash
# Good - Redis CLI로 TTL 확인
#!/bin/bash
# check_zset_ttl.sh

TODAY=$(date +%Y-%m-%d)
KEY="popular:characters:${TODAY}"

# TTL 확인 (초 단위)
TTL=$(redis-cli TTL "$KEY")

if [ "$TTL" -eq -1 ]; then
    echo "ERROR: No TTL set on $KEY"
    exit 1
elif [ "$TTL" -eq -2 ]; then
    echo "INFO: Key $KEY does not exist"
    exit 0
else
    HOURS=$((TTL / 3600))
    echo "OK: TTL=$TTL seconds ($HOURS hours)"
fi

# ZSET 크기 확인
SIZE=$(redis-cli ZCARD "$KEY")
echo "ZSET size: $SIZE members"

# 메모리 사용량 추정
MEMORY=$((SIZE * 28))
echo "Estimated memory: $MEMORY bytes ($(($MEMORY / 1024))KB)"
```

### 6. TTL 테스트

```java
// Good - TTL 설정 테스트
@Test
@DisplayName("ZSET TTL 48시간 설정 검증")
void zsetShouldHave48HourTtl() {
    // Given
    PopularCharacterTracker tracker = new PopularCharacterTracker(redissonClient);
    String ign = "testCharacter";

    // When - 점수 기록
    tracker.recordAccess(ign);

    // Then - TTL 확인
    String key = "popular:characters:" + LocalDate.now();
    RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);

    long remainTimeToLive = zset.remainTimeToLive();
    long expectedTtl = 48 * 60 * 60 * 1000;  // 48시간 (ms)

    // 48시간 ± 10분 오차 허용
    assertThat(remainTimeToLive)
        .isGreaterThan(expectedTtl - 10 * 60 * 1000)
        .isLessThan(expectedTtl + 10 * 60 * 1000);
}
```

## TTL 설정 가이드

| 사용 사례 | 권장 TTL | 설명 |
|----------|----------|------|
| **인기 캐릭터 추적** | 48시간 | 당일+전날 데이터 유지 |
| **세션 저장** | 24시간 | 일일 세션 유지 |
| **임시 캐시** | 1-6시간 | 단기 캐싱 |
| **장기 캐시** | 7-30일 | 장기 데이터 보존 |

## 메모리 사용량 계산

```
ZSET 한 키당 메모리 사용량:
- Member (userIgn): 평균 20 bytes
- Score (Double): 8 bytes
- Redis 오버헤드: 약 10 bytes
- 합계: 약 38 bytes/member

10,000 캐릭터 × 38 bytes ≈ 380KB/day

TTL별 메모리 사용량:
- 48시간 (2일): 380KB × 2 = 760KB
- 7일: 380KB × 7 = 2.66MB
- 30일: 380KB × 30 = 11.4MB
- 무제한: 380KB × 365 = 138MB (1년 후)
```

## TTL 만료 시나리오

```
[Day 1: 2026-02-01]
00:00 - ZSET 생성
00:00 - TTL 48시간 설정
...
23:59 - TTL 24시간 남음

[Day 2: 2026-02-02]
00:00 - TTL 24시간 남음 (전날 데이터 웜업 가능)
00:00 - 새로운 ZSET 생성
...
23:59 - 첫 번째 ZSET TTL 0시간 남음

[Day 3: 2026-02-03]
00:00 - 첫 번째 ZSET 만료 및 자동 삭제
00:00 - TTL 24시간 남음 (전날 데이터 웜업 가능)
...
```

## 출처

### 문서
- `docs/03_Technical_Guides/auto-warmup.md` Section 2.2: Redis 데이터 구조

### ADR
- Issue #275: Auto Warmup 기능 구현 (TTL 설정)

### 코드 (Evidence)
- `src/main/java/maple/expectation/service/v4/warmup/PopularCharacterTracker.java`

## 검증 명령어

```bash
# TTL 설정 확인
redis-cli TTL "popular:characters:$(date +%Y-%m-%d)"

# ZSET 크기 확인
redis-cli ZCARD "popular:characters:$(date +%Y-%m-%d)"

# 상위 10개 캐릭터 확인
redis-cli ZREVRANGE "popular:characters:$(date +%Y-%m-%d)" 0 9 WITHSCORES

# 모든 ZSET 키 확인
redis-cli KEYS "popular:characters:*"

# 메모리 사용량 확인
redis-cli MEMORY USAGE "popular:characters:$(date +%Y-%m-%d)"

# TTL 테스트 실행
./gradlew test --tests "*PopularCharacterTrackerTest"
```
