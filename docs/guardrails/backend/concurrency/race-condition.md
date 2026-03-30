---
id: GR-CONC-005
category: backend/concurrency
severity: critical
keywords: [RaceCondition, AtomicUpdate, HotRow, LuaScript, Counter]
languages: [java, kotlin]
---
# Race Condition Prevention

## DON'T (안티패턴)

### 1. Hot Row에서 Pessimistic Lock 사용 (성능 저하)
```java
// Bad (Hot Row 경합으로 처리량 120 TPS로 저하)
@Repository
public class GameCharacterRepository {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM GameCharacter c WHERE c.userIgn = :userIgn")
    Optional<GameCharacter> findByUserIgnWithLock(@Param("userIgn") String userIgn);
}

@Service
public class LikeService {
    public void incrementLikeCount(String userIgn) {
        GameCharacter character = repository.findByUserIgnWithLock(userIgn)
                .orElseThrow();
        character.setLikeCount(character.getLikeCount() + 1);  // Race Condition 위험
        repository.save(character);
    }
}
```

**문제점:**
- 인기 캐릭터(hot row)는 초당 수백 건의 좋아요 요청
- Pessimistic Lock으로 대기열 발생 -> 처리량 120 TPS로 급락
- 각 트랜잭션이 이전 트랜잭션 커밋을 대기 -> 병목

### 2. Read-Modify-Write 패턴 (Race Condition)
```java
// Bad (Race Condition: Lost Update)
public void incrementLikeCount(String userIgn) {
    GameCharacter character = repository.findByUserIgn(userIgn).orElseThrow();
    Long current = character.getLikeCount();  // READ
    // ... 다른 스레드가 여기서 수정할 수 있음
    character.setLikeCount(current + 1);  // MODIFY
    repository.save(character);  // WRITE (Lost Update 발생 가능)
}
```

### 3. Redis INCR 없이 분산 카운터 구현
```java
// Bad (Race Condition in Redis)
public Long incrementLikeCount(String userIgn) {
    String key = "like:" + userIgn;
    String current = redisTemplate.opsForValue().get(key);  // GET
    Long newValue = Long.parseLong(current) + 1;
    redisTemplate.opsForValue().set(key, newValue.toString());  // SET (Race!)
    return newValue;
}
```

## DO (베스트 프랙티스)

### 1. Atomic Update (Hot Row에 최적)
```java
// Good (Atomic Update: 1,200 TPS 달성 - 10x 향상)
@Repository
public interface GameCharacterRepository extends JpaRepository<GameCharacter, Long> {

    @Modifying
    @Query("UPDATE GameCharacter c SET c.likeCount = c.likeCount + :count WHERE c.userIgn = :userIgn")
    void incrementLikeCount(@Param("userIgn") String userIgn, @Param("count") Long count);
}

@Service
public class LikeService {
    public void incrementLikeCount(String userIgn) {
        repository.incrementLikeCount(userIgn, 1L);  // 단일 쿼리로 원자적 증가
    }
}
```

**Atomic Update 장점:**
- 단일 UPDATE 문 -> DB가 Row Lock 획득 후 즉시 해제
- 대기열 없음 -> Hot Row에서도 1,200 TPS 달성 (10x 향상)
- MySQL InnoDB가 단일 UPDATE 문의 원자성 보장

### 2. Redis Lua Script로 원자적 연산
```java
// Good (Lua Script: Redis에서 원자적 GETDEL)
@Component
public class LuaScriptAtomicFetchStrategy {

    private final RedisScript<List> script;
    private final StringRedisTemplate redisTemplate;

    public LuaScriptAtomicFetchStrategy() {
        // Lua Script: 원자적 HGETALL + DEL
        this.script = RedisScript.of("""
            local data = redis.call('HGETALL', KEYS[1])
            if #data > 0 then
                redis.call('DEL', KEYS[1])
            end
            return data
            """, List.class);
    }

    public Map<String, String> fetchAndDelete(String key) {
        // Lua Script는 Redis에서 단일 명령어로 실행 -> Race Condition 없음
        return redisTemplate.execute(script, Collections.singletonList(key));
    }
}
```

**Lua Script 장점:**
- Redis에서 단일 명령어로 실행 -> 원자성 보장
- `HGETALL` + `DEL`을 원자적으로 수행
- EVAL 명령어 전체가 실행될 때까지 다른 명령어 차단

### 3. TieredCache + 비동기 Flush (최종 일관성)
```java
// Good (요청 시점은 락 없음, 스케줄러에서 동기화)
@Service
public class LikeWriteService {

    private final MemoryLikeBuffer memoryBuffer;  // L1: Memory
    private final StringRedisTemplate redisTemplate;  // L2: Redis

    // 1. 요청 시점: 락 없이 버퍼에 추가 (고처리량)
    public void incrementLikeCount(String userIgn, Long delta) {
        memoryBuffer.increment(userIgn, delta);  // Memory (ConcurrentHashMap)
        redisTemplate.opsForHash().increment("like:buffer", userIgn, delta);  // Redis INCR (원자적)
    }
}

// 2. 스케줄러: Redis → DB 동기화 (분산 락 사용)
@Component
public class LikeSyncScheduler {

    private final LockStrategy lockStrategy;

    @Scheduled(fixedDelay = 3000)
    public void syncRedisToDatabase() {
        lockStrategy.executeWithLock("like-db-sync-lock", 0, 30, () -> {
            Map<String, String> buffer = fetchStrategy.fetchAndDelete("like:buffer");
            buffer.forEach((userIgn, count) ->
                repository.incrementLikeCount(userIgn, Long.parseLong(count)));
            return null;
        });
    }
}
```

### 4. Redis INCR/DECR (분산 카운터)
```java
// Good (Redis INCR: 원자적 증가)
public Long incrementLikeCount(String userIgn) {
    String key = "like:" + userIgn;
    return redisTemplate.opsForValue().increment(key);  // INCR (원자적)
}

// Good (Redis INCRBY: bulk increment)
public void incrementLikeCountBulk(String userIgn, Long delta) {
    String key = "like:" + userIgn;
    redisTemplate.opsForValue().increment(key, delta);  // INCRBY (원자적)
}
```

### 5. 낙관적 락 (@Version)으로 충돌 감지
```java
// Good (낙관적 락: 충돌 시 재시도)
@Entity
public class GameCharacter {

    @Id
    private String userIgn;

    private Long likeCount;

    @Version  // JPA 낙관적 락
    private Long version;
}

@Service
public class LikeService {

    @Retryable(
        value = OptimisticLockException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100)
    )
    public void incrementLikeCountWithRetry(String userIgn) {
        GameCharacter character = repository.findByUserIgn(userIgn).orElseThrow();
        character.setLikeCount(character.getLikeCount() + 1);
        repository.save(character);  // @Version으로 충돌 감지, 재시도
    }
}
```

### 6. 전략별 성능 비교
| 전략 | 처리량 (TPS) | 지연시간 | 일관성 | 적합 케이스 |
|------|--------------|---------|--------|------------|
| **Pessimistic Lock** | 120 | 높음 (대기 발생) | 강한 | 금융 트랜잭션 |
| **Atomic Update** | 1,200 | 낮음 | 강한 | 카운터 증감 |
| **Redis INCR + 비동기 Flush** | 10,000+ | 매우 낮음 | 최종 | 좋아요, 조회수 |
| **낙관적 락 (@Version)** | 500-1,000 | 중간 | 강한 | 충돌 드문 수정 |

### 7. 도메인별 락 전략 선택 가이드
```java
// 좋아요 (likeCount): Atomic Update (성능 우선)
@Query("UPDATE GameCharacter c SET c.likeCount = c.likeCount + :count WHERE c.userIgn = :userIgn")
void incrementLikeCount(@Param("userIgn") String userIgn, @Param("count") Long count);

// 후원 (Donation): SKIP LOCKED (분산 배치)
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))  // SKIP LOCKED
@Query("SELECT o FROM DonationOutbox o WHERE o.status IN :statuses ORDER BY o.id")
List<DonationOutbox> findPendingWithLock(...);

// 캐릭터 수정: Pessimistic Lock (무결성 우선)
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM GameCharacter c WHERE c.userIgn = :userIgn")
Optional<GameCharacter> findByUserIgnWithPessimisticLock(@Param("userIgn") String userIgn);
```

## 출처
- lock-strategy.md Section 1 (좋아요 도메인)
- Performance Evidence: Atomic Update 1,200 TPS vs Pessimistic Lock 120 TPS (10x)
- Load Test: `docs/05_Reports/Cost_Performance/N23_WRK_V4_RESULTS.md`

## 검증 명령어
```bash
# Atomic Update 사용 확인
grep -r "SET.*=.*\+" src/main/kotlin --include="*.java" | grep UPDATE

# Redis INCR 사용 확인
grep -r "increment(" src/main/kotlin --include="*.java" | grep redisTemplate

# @Version 사용 확인
grep -r "@Version" src/main/kotlin --include="*.java"

# Pessimistic Lock 사용 확인 (Hot Row 주의)
grep -r "PESSIMISTIC_WRITE" src/main/kotlin --include="*.java"
```

## 롤백 계획
- Atomic Update 정확도 문제 발생 시: Pessimistic Lock으로 복구
- Redis Lua Script 성능 저하 시: Redis Transaction (MULTI/EXEC)으로 대체
