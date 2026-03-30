---
id: GR-ARCH-003
category: architecture
severity: critical
keywords: [HttpSession, @SessionScope, @SessionAttributes, session, stateful, static mutable, Redis, MySQL, MongoDB, Kafka]
---

# Stateless Architecture Guardrails

## Overview

probabilistic-valuation-engine은 **완전한 Stateless 아키텍처**로 설계되어야 합니다. 모든 상태는 인프라 계층(Redis, MySQL, MongoDB, Kafka)에 위임하여 수평 확장성(Horizontal Scalability)을 확보합니다.

---

## GR-ARCH-003-1: HttpSession 사용 금지

### DON'T (안티패턴)

```java
// 안티패턴 1: HttpSession 사용
@GetMapping("/user")
public User getUser(HttpSession session) {
    return (User) session.getAttribute("user");  // ❌ 서버에 상태 저장
}

// 안티패턴 2: Session Scope Bean
@SessionScope
@Service
public class UserStateService {
    private User currentUser;  // ❌ 인스턴스 상태
}

// 안티패턴 3: SessionAttributes
@Controller
@SessionAttributes("cart")
public class CartController {
    // ❌ 모델 상태가 세션에 저장됨
}
```

**위험성:**
- 수평 확장 불가능 (Sticky Session 필요)
- 서버 간 세션 동기화 문제
- 메모리 누수 가능성
- 장애 시 세션 소실

### DO (베스트 프랙티스)

```java
// Good: Redis에 상태 저장
@Service
@RequiredArgsConstructor
public class UserStateService {
    private final RedisTemplate<String, User> redisTemplate;

    public User getUser(String sessionId) {
        return redisTemplate.opsForValue()
            .get("session:" + sessionId + ":user");
    }

    public void setUser(String sessionId, User user) {
        redisTemplate.opsForValue()
            .set("session:" + sessionId + ":user", user, Duration.ofHours(24));
    }
}

// Good: JWT 토큰 사용 (Stateless Authentication)
@Service
@RequiredArgsConstructor
public class JwtTokenProvider {
    private final RedisTemplate<String, String> blacklistCache;

    public String createToken(String userId) {
        // ✅ 토큰 자체에 클레임 포함 (서버 상태 불필요)
        return Jwts.builder()
            .setSubject(userId)
            .setExpiration(new Date(System.currentTimeMillis() + 86400000))
            .signWith(key, SignatureAlgorithm.HS512)
            .compact();
    }

    public boolean validateToken(String token) {
        String jti = getJti(token);
        // ✅ Redis 블랙리스트만 확인 (서버 상태 아님)
        return !Boolean.TRUE.equals(blacklistCache.hasKey("blacklist:" + jti));
    }
}
```

### 출처
- CLAUDE.md Section 18: Stateless Architecture Principles

---

## GR-ARCH-003-2: static mutable 상태 금지

### DON'T (안티패턴)

```java
// 안티패턴 1: static mutable 컬렉션
public class OnlineUsersTracker {
    private static final Set<String> onlineUsers = new ConcurrentHashMap<>();  // ❌

    public static void addOnlineUser(String userId) {
        onlineUsers.add(userId);
    }
}

// 안티패턴 2: Singleton 상태
@Service
public class RequestCounter {
    private final AtomicLong counter = new AtomicLong(0);  // ❌ 서버별 카운트

    public void increment() {
        counter.incrementAndGet();
    }
}
```

**위험성:**
- 여러 서버 인스턴스 시 데이터 불일치
- 장애 복구 시 데이터 소실
- 분산 처리 불가능

### DO (베스트 프랙티스)

```java
// Good: Redis Sorted Set으로 온라인 사용자 추적
@Service
@RequiredArgsConstructor
public class OnlineUsersTracker {
    private final RedisTemplate<String, String> redisTemplate;

    public void addOnlineUser(String userId) {
        // ✅ ZADD with current timestamp as score
        redisTemplate.opsForZSet()
            .add("online:users", userId, System.currentTimeMillis());
    }

    public long getOnlineUserCount() {
        // ✅ Remove inactive users (heartbeat > 5 minutes ago)
        long cutoff = System.currentTimeMillis() - 300000;
        redisTemplate.opsForZSet().removeRangeByScore("online:users", 0, cutoff);
        return redisTemplate.opsForZSet().size("online:users");
    }
}

// Good: Redis Counter로 전역 카운트
@Service
@RequiredArgsConstructor
public class RequestCounter {
    private final RedisTemplate<String, Long> redisTemplate;

    public void increment() {
        // ✅ INCR is atomic across all servers
        redisTemplate.opsForValue()
            .increment("counter:requests", 1);
    }
}
```

### 출처
- CLAUDE.md Section 18: Stateless Architecture Principles

---

## GR-ARCH-003-3: 상태 저장소 가이드

### 상태별 적합한 저장소

| 상태 타입 | 적합한 저장소 | 이유 | 예시 |
|----------|--------------|------|------|
| **세션 데이터** | Redis | 빠른 읽기/쓰기, TTL 지원 | `session:{userId}` |
| **캐시** | Redis (L2) + Caffeine (L1) | 분산 캐시 + 로컬 캐시 | `equipment:{ocid}` |
| **영구 데이터** | MySQL | ACID 트랜잭션, 복잡한 쿼리 | 캐릭터, 장비 데이터 |
| **읽기 전용 뷰** | MongoDB | 문서 모델, 유연한 스키마 | V5 Query Side |
| **이벤트 스트림** | Kafka | 순서 보장, 재생 가능 | 도네이션 이벤트 |
| **분산 락** | Redis (Redisson) | RLock, 분산 상호 배제 | 좋아요 중복 방지 |

### 저장소 선택 가이드

```java
// Good: 각 상태에 맞는 저장소 선택
@Service
@RequiredArgsConstructor
public class CharacterService {
    // 1. 빠른 액세스 캐시
    private final TieredCacheManager cache;  // Caffeine + Redis

    // 2. 영구 데이터
    private final CharacterRepository repository;  // MySQL

    // 3. 분산 락
    private final RedissonClient redisson;  // Redis

    // 4. 이벤트 발행
    private final KafkaTemplate<String, String> kafka;  // Kafka
}
```

### 출처
- CLAUDE.md Section 18: Stateless Architecture Principles
- [architecture.md](../00_Start_Here/architecture.md) - Section 4: Redis HA Architecture

---

## GR-ARCH-003-4: Write-Behind 버퍼와 Graceful Shutdown

### DON'T (안티패턴)

```java
// 안티패턴: 서버 종료 시 버퍼 데이터 손실
@PreDestroy
public void shutdown() {
    // ❌ 버퍼 플러시 없이 종료
}
```

**위험성:**
- 진행 중인 좋아요/기댓값 데이터 소실
- 데이터 정합성 파괴

### DO (베스트 프랙티스)

```java
// Good: 3-Phase Graceful Shutdown
@Component
@RequiredArgsConstructor
public class ExpectationBatchShutdownHandler
        implements SmartLifecycle {

    private final ExpectationWriteBackBuffer buffer;
    private final ExpectationPersistenceService persistence;

    @Override
    public void stop() {
        // Phase 1: Block new writes
        buffer.block();  // CAS-based blocking

        // Phase 2: Wait for in-flight tasks (Phaser prevents race)
        buffer.awaitQuiescence(30, TimeUnit.SECONDS);

        // Phase 3: Drain remaining buffer
        List<ExpectationWriteTask> remaining = buffer.drain();
        if (!remaining.isEmpty()) {
            persistence.syncFlush(remaining);  // Synchronous flush
        }
    }
}
```

### 출처
- CLAUDE.md Section 16: Proactive Refactoring & Quality
- [service-modules.md](../03_Technical_Guides/service-modules.md) - Section 2: buffer

---

## Verification Commands

```bash
# HttpSession 사용 검증
grep -r "HttpSession" src/main/kotlin/ | grep -v "import" | wc -l
# Expected: 0

# @SessionScope 사용 검증
grep -r "@SessionScope" src/main/kotlin/ | wc -l
# Expected: 0

# static mutable 컬렉션 검증
grep -r "static.*Map\|static.*Set\|static.*List" src/main/kotlin/ | wc -l
# Expected: 0 (except constants)

# Redis 상태 저장 확인
redis-cli --scan --pattern "session:*" | wc -l
# Expected: > 0 (sessions stored in Redis)
```

---

## Evidence Links

- [architecture.md](../00_Start_Here/architecture.md) - Section 4: Redis HA Architecture
- [service-modules.md](../03_Technical_Guides/service-modules.md) - Section 2: buffer (Write-Behind)
- CLAUDE.md Section 18: Stateless Architecture Principles
