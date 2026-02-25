---
id: GR-NIGHTMARE-001
category: testing
severity: critical
keywords: [Nightmare, Cache, Stampede, Singleflight, DB, Deadlock, Lock, Timeout, Cascade]
---

# Nightmare Scenarios (N01-N19)

## 개요

MapleExpectation 프로젝트의 **Chaos Engineering Nightmare 시나리오**를 정의합니다. 각 시나리오는 **운영 환경에서 발생 가능한 실제 장애**를 기반으로 하며, 시스템의 **회복 탄력성(Resilience)**을 검증합니다.

> **Total Scenarios:** N01-N19 (19개 시나리오)
> **Test Evidence:** All scenarios include reproducible test results with Before/After metrics

---

## Nightmare 시나리오 요약

| ID | 시나리오 | 문제 | 해결 | 난이도 |
|----|---------|------|------|--------|
| N01 | Thundering Herd | Cache Stampede | Singleflight | P0 |
| N02 | Deadlock Trap | Circular Lock | Lock Ordering | P0 |
| N03 | Thread Pool Exhaustion | CallerRunsPolicy | AbortPolicy | P0 |
| N04 | Connection Vampire | 트랜잭션 내 API 호출 | API 호출 분리 | P0 |
| N05 | Celebrity Problem | Hot Key 경합 | Singleflight + Sharding | P1 |
| N06 | Timeout Cascade | 타임아웃 계층 불일치 | 계층 정렬 | P0 |
| N07 | Metadata Lock Freeze | DDL + DML 경합 | DDL 분리 | P1 |
| N08 | Thundering Herd + Redis Death | 이중 장애 | Fallback | P0 |
| N09 | Circular Lock Deadlock | 락 순환 의존 | 락 순서 지정 | P0 |
| N10 | Caller Runs Policy | 메인 스레드 블로킹 | AbortPolicy | P0 |
| N11 | Lock Fallback Avalanche | 분산락 실패 시 폴백 | 제한적 폴백 | P1 |
| N12 | Async Context Loss | MDC 손실 | TaskDecorator | P1 |
| N13 | Zombie Outbox | 고립된 레코드 | 멱등성 재시도 | P1 |
| N14 | Pipeline Exception | 파이프라인 중단 | 예외 처리 | P1 |
| N15 | AOP Order Problem | 순서 비결정 | @Order 지정 | P1 |
| N16 | Self-Invocation Mirage | AOP 우회 | 별도 Bean 분리 | P1 |
| N17 | Poison Pill | 잘못된 메시지 | Dead Letter Queue | P1 |
| N18 | Deep Paging | Deep Cursor | 커서 기반 페이징 | P1 |
| N19 | Outbox Replay | API 장애 복구 | 멱등성 재시도 | P0 |

---

# Nightmare 01: Thundering Herd (Cache Stampede)

## DON'T (안티패턴)

```java
// Bad: Singleflight 없이 모든 요청이 DB로 직행
@Cacheable(value = "characters", key = "#userIgn")
public GameCharacter getCharacter(String userIgn) {
    return repository.findByUserIgn(userIgn);
}
```

### 잘못된 장애 주입 방법
```bash
# 비현실적인 전체 캐시 삭제
redis-cli FLUSHALL
```

### 실패 징후
- DB Query Rate > 10 qps (정상: 5 qps)
- Connection Pool Active = 최대치
- Cache Hit Rate = 0%

## DO (베스트 프랙티스)

```java
// Good: TieredCache에 Singleflight 구현
private <T> T getWithSingleflight(Object key, Callable<T> loader) {
    String lockKey = "singleflight:" + keyStr.hashCode();
    RLock lock = redissonClient.getLock(lockKey);

    if (lock.tryLock(30, 30, TimeUnit.SECONDS)) {
        try {
            return loader.call();  // 1개만 DB 조회
        } finally {
            lock.unlock();
        }
    } else {
        // Double-check: 다른 스레드가 캐시를 채웠는지 확인
        T cached = getFromL2(key);
        if (cached != null) {
            return cached;
        }
        return loader.call();  // 최후의 Fallback
    }
}
```

### 현실적인 장애 주입
```bash
# 시나리오 A: 특정 키만 삭제
redis-cli DEL nightmare:test:key

# 시나리오 B: TTL 자연 만료
redis-cli SET nightmare:test:key "value" EX 1 && sleep 1

# 시나리오 C: L1/L2 계층별 선택적 무효화
# L1만: Caffeine.clear() 후 Redis 유지
# L2만: redis-cli DEL 후 Caffeine 유지
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| DB Query Ratio | ≤ 1% | > 10% |
| Response Time p99 | < 5000ms | ≥ 5000ms |
| Data Consistency | 100% | < 100% |

---

# Nightmare 02: Deadlock Trap (Circular Lock)

## DON'T (안티패턴)

```java
// Bad: 두 트랜잭션이 서로 다른 순서로 락 획득
@Transactional
public void transactionA() {
    tableARepository.update(id, value);  // TABLE_A 먼저
    tableBRepository.update(id, value);  // TABLE_B 나중
}

@Transactional
public void transactionB() {
    tableBRepository.update(id, value);  // TABLE_B 먼저
    tableARepository.update(id, value);  // TABLE_A 나중
}
```

### 실패 징후
- MySQL Deadlocks > 0
- Transaction Rollbacks > 0
- Lock Wait Timeout 발생

## DO (베스트 프랙티스)

```java
// Good: Lock Ordering 패턴 적용
public class LockOrderingHelper {
    public static List<String> getOrderedTables(String... tables) {
        return Arrays.stream(tables)
                .sorted()  // 알파벳순 정렬
                .toList();
    }
}
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| Deadlock 발생 | 0건 | ≥ 1건 |
| 데이터 무결성 | 100% | < 100% |
| 트랜잭션 완료율 | 100% | < 100% |

---

# Nightmare 03: Thread Pool Exhaustion

## DON'T (안티패턴)

```java
// Bad: CallerRunsPolicy 사용
@Bean
public Executor executor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setRejectedExecutionHandler(new CallerRunsPolicy());  // 위험!
    return executor;
}
```

### 실패 징후
- Main Thread Blocked = Yes
- Response Time > 30초
- Active Requests 계속 증가

## DO (베스트 프랙티스)

```java
// Good: AbortPolicy + Fallback 적용
@Bean
public Executor expectationComputeExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setRejectedExecutionHandler((r, e) -> {
        rejectedCounter.increment();
        throw new RejectedExecutionException("Queue full");
    });
    return executor;
}
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| CallerRunsPolicy 발동 | 0회 | ≥ 1회 |
| 작업 제출 시간 | < 500ms | ≥ 500ms |

---

# Nightmare 04: Connection Vampire (DB Pool Starvation)

## DON'T (안티패턴)

```java
// Bad: 트랜잭션 범위 내에서 블로킹 API 호출
@Transactional(propagation = Propagation.REQUIRES_NEW)
public GameCharacter createNewCharacter(String userIgn) {
    String ocid = nexonApiClient.getOcidByCharacterName(userIgn)
        .join();  // BLOCKING! 최대 28초 동안 DB 커넥션 점유
    return gameCharacterRepository.save(new GameCharacter(userIgn, ocid));
}
```

### 실패 징후
- Connection Timeout 발생
- HikariCP Active Connections = Pool 최대치
- Pending Threads > 0

## DO (베스트 프랙티스)

```java
// Good: API 호출을 트랜잭션 밖으로 분리
public GameCharacter createNewCharacter(String userIgn) {
    // 1. API 호출 (트랜잭션 밖)
    String ocid = nexonApiClient.getOcidByCharacterName(userIgn).join();

    // 2. DB 작업 (트랜잭션 안)
    return saveCharacter(userIgn, ocid);
}
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| Connection Timeout | 0건 | ≥ 1건 |
| Pool 사용률 | < 80% | = 100% |

---

# Nightmare 05: Celebrity Problem (Hot Key Meltdown)

## DON'T (안티패턴)

```java
// Bad: 모든 요청이 단일 키로 집중
@Cacheable(value = "characters", key = "#userIgn")
public GameCharacter getCharacter(String userIgn) {
    return repository.findByUserIgn(userIgn);
}
```

### 실패 징후
- DB Query Rate > 100 qps
- Lock Contention > 50%
- Response Time p99 > 5000ms

## DO (베스트 프랙티스)

```java
// Good: Singleflight + Hot Key Sharding
private <T> T getWithSingleflight(Object key, Callable<T> loader) {
    String lockKey = "singleflight:" + keyStr.hashCode();
    RLock lock = redissonClient.getLock(lockKey);

    if (lock.tryLock(5, 5, TimeUnit.SECONDS)) {
        try {
            return loader.call();  // 1개만 DB 조회
        } finally {
            lock.unlock();
        }
    } else {
        T cached = getFromL2(key);
        if (cached != null) {
            return cached;
        }
        return loader.call();
    }
}
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| DB 쿼리 비율 | ≤ 1% | > 10% |
| Lock Failure | < 5% | > 50% |

---

# Nightmare 06: Timeout Cascade (Zombie Request)

## DON'T (안티패턴)

```yaml
# Bad: 클라이언트 타임아웃 < 서버 처리 체인
client:
  timeout: 3s

resilience4j:
  timelimiter:
    instances:
      default:
        timeoutDuration: 28s  # 클라이언트보다 김!
```

### 실패 징후
- Zombie Request Count > 0
- Resource Waste Time > 0s
- Thread Pool Active > Pool Size

## DO (베스트 프랙티스)

```yaml
# Good: 클라이언트 > 서버 처리 체인
client:
  timeout: 10s

resilience4j:
  timelimiter:
    instances:
      default:
        timeoutDuration: 8s  # 28s → 8s로 단축
        cancelRunningFuture: true
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| Zombie Request | 0건 | ≥ 1건 |
| 리소스 낭비 시간 | 0초 | > 10초 |

---

# Nightmare 07: Metadata Lock Freeze

## DON'T (안티패턴)

```sql
-- Bad: DDL과 DML 동시 실행
-- Session 1: DDL (테이블 구조 변경)
ALTER TABLE members ADD COLUMN temp VARCHAR(255);

-- Session 2: DML (데이터 조회/삽입)
SELECT * FROM members WHERE id = 1;
-- 결과: Metadata Lock Wait...
```

### 실패 징후
- Metadata Lock Wait > 0s
- DDL 실행 중 모든 DML 차단
- Table Copy 발생 (디스크 I/O 폭증)

## DO (베스트 프랙티스)

```java
// Good: DDL을 유지보수 시간에만 실행
@Scheduled(cron = "0 0 3 * * ?")  // 새벽 3시에만 실행
public void runDdlOnlyInMaintenanceWindow() {
    // DDL 실행
}

// 또한 Online DDL 사용
ALTER TABLE members ADD COLUMN temp VARCHAR(255), ALGORITHM=INPLACE, LOCK=NONE;
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| Metadata Lock Wait | 0s | > 0s |
| DML 차단 시간 | 0s | > 0s |

---

# Nightmare 08: Thundering Herd + Redis Death (이중 장애)

## DON'T (안티패턴)

```java
// Bad: Redis 장애 시 폴백 없이 DB 직행
public GameCharacter getCharacter(String userIgn) {
    try {
        return tieredCache.get(key, loader);
    } catch (RedisConnectionFailureException e) {
        return repository.findByUserIgn(userIgn);  // 1000개 요청이 모두 DB로!
    }
}
```

### 실패 징후
- Redis Down + Cache Miss 시 DB Query Rate 폭증
- Connection Pool 고갈
- 전체 API 마비

## DO (베스트 프랙티스)

```java
// Good: Redis 장애 시에도 Singleflight 유지
public GameCharacter getCharacter(String userIgn) {
    String key = "character:" + userIgn;

    // Redis 장애와 무관하게 Singleflight 락 획득
    return tieredCache.get(key, () -> {
        // Redis 다운 시에도 DB는 1회만 조회
        return repository.findByUserIgn(userIgn);
    });
}
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| Redis Down 시 DB Query | 1회 | 1000회 |
| Connection Pool 고갈 | No | Yes |

---

# Nightmare 09: Circular Lock Deadlock

N02와 유사하지만 **분산락(Redisson)** 환경에서의 Deadlock 시나리오입니다.

### 실패 징후
- Redisson Lock acquisition timeout
- 분산락 경합 발생
- 요청 타임아웃

## DO (베스트 프랙티스)

```java
// Good: 락 순서 지정 + 타임아웃
public void updateWithOrderedLocks(String id1, String id2) {
    // 항상 작은 ID부터 락 획득
    String firstLock = id1.compareTo(id2) < 0 ? id1 : id2;
    String secondLock = id1.compareTo(id2) < 0 ? id2 : id1;

    RLock lock1 = redissonClient.getLock(firstLock);
    RLock lock2 = redissonClient.getLock(secondLock);

    lock1.lock();
    try {
        lock2.lock();
        try {
            // 비즈니스 로직
        } finally {
            lock2.unlock();
        }
    } finally {
        lock1.unlock();
    }
}
```

---

# Nightmare 10: Caller Runs Policy

N03와 동일합니다. ThreadPoolExecutor의 **CallerRunsPolicy**가 메인 스레드를 블로킹하는 시나리오입니다.

---

# Nightmare 11: Lock Fallback Avalanche

## DON'T (안티패턴)

```java
// Bad: 분산락 실패 시 모두 DB 직행
public T getWithLock(String key, Callable<T> loader) {
    RLock lock = redissonClient.getLock(key);
    if (lock.tryLock()) {
        try {
            return loader.call();
        } finally {
            lock.unlock();
        }
    } else {
        // 분산락 실패 시 모두 DB 조회
        return loader.call();  // ❌ Cache Stampede!
    }
}
```

### 실패 징후
- 분산락 실패 시 DB Query Rate 폭증
- Redis 장애 전이

## DO (베스트 프랙티스)

```java
// Good: 분산락 실패 시에도 캐시 재확인
public T getWithLock(String key, Callable<T> loader) {
    RLock lock = redissonClient.getLock(key);
    if (lock.tryLock()) {
        try {
            T result = loader.call();
            cache.put(key, result);
            return result;
        } finally {
            lock.unlock();
        }
    } else {
        // 분산락 실패 시 캐시 재확인
        T cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;  // 캐시 HIT하면 DB 조회 안 함
        }
        // 최후의 수단: 제한적 재시도
        return loadWithBackoff(key, loader);
    }
}
```

---

# Nightmare 12: Async Context Loss (Phantom Context)

## DON'T (안티패턴)

```java
// Bad: MDC 컨텍스트 전파 없음
@Bean
public Executor asyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    // TaskDecorator 미설정!
    return executor;
}

@Service
public class OrderService {
    public void processOrder(String orderId) {
        MDC.put("orderId", orderId);

        asyncExecutor.execute(() -> {
            String id = MDC.get("orderId");  // NULL! 컨텍스트 손실
            log.info("Processing order");
        });
    }
}
```

### 실패 징후
- MDC Propagation Rate < 100%
- 분산 추적(Tracing) 끊김
- 감사 로그에 사용자 정보 없음

## DO (베스트 프랙티스)

```java
// Good: MdcCopyingTaskDecorator로 컨텍스트 자동 전파
@Configuration
public class ExecutorConfig {
    @Bean
    public Executor alertTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(new MdcCopyingTaskDecorator());
        return executor;
    }
}

public class MdcCopyingTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| MDC Propagation Rate | 100% | < 100% |
| SecurityContext Propagation | 100% | < 100% |

---

# Nightmare 13: Zombie Outbox

## DON'T (안티패턴)

```java
// Bad: Outbox 재시도 시 멱등성 확인 없음
@Scheduled(fixedDelay = 1000)
public void replayOutbox() {
    List<Outbox> pending = outboxRepository.findPending();
    for (Outbox entry : pending) {
        try {
            apiClient.call(entry);
            entry.markAsCompleted();
        } catch (Exception e) {
            entry.markAsFailed(e);
            // 재시도하지 않음 → 고립된 레코드
        }
    }
}
```

### 실패 징후
- Orphaned Outbox records 누적
- 메시지 유실 가능성
- 데이터 불일치

## DO (베스트 프랙티스)

```java
// Good: requestId 기반 멱등성 재시도
@Scheduled(fixedDelay = 1000)
public void replayOutbox() {
    List<Outbox> pending = outboxRepository.findPendingForReplay(LOCKED, 100);

    for (Outbox entry : pending) {
        try {
            // requestId로 멱등성 확인
            if (apiClient.isAlreadyProcessed(entry.getRequestId())) {
                entry.markAsCompleted();
                continue;
            }

            apiClient.call(entry);
            entry.markAsCompleted();
        } catch (Exception e) {
            entry.incrementRetryCount();
            if (entry.getRetryCount() >= MAX_RETRY) {
                entry.markAsFailed(e);
            }
        }
    }
}
```

---

# Nightmare 14: Pipeline Exception

## DON'T (안티패턴)

```java
// Bad: 파이프라인 중단 시 예외 전파
public void processPipeline(List<Stage> stages) {
    for (Stage stage : stages) {
        stage.execute();  // 예외 발생 시 이후 Stage 미실행
    }
}
```

### 실패 징후
- 파이프라인 중단
- 부분 처리만 완료
- 데이터 불일치

## DO (베스트 프랙티스)

```java
// Good: 각 Stage 예외 독립 처리
public void processPipeline(List<Stage> stages) {
    PipelineResult result = new PipelineResult();

    for (Stage stage : stages) {
        try {
            stage.execute();
            result.markStageSuccess(stage.getName());
        } catch (Exception e) {
            result.markStageFailure(stage.getName(), e);
            // 다음 Stage 계속 실행
        }
    }

    // 최종 결과 집계
    if (result.hasFailures()) {
        handlePartialFailure(result);
    }
}
```

---

# Nightmare 15: AOP Order Problem

## DON'T (안티패턴)

```java
// Bad: @Order 미지정
@Aspect
public class AuditAspect {  // Order 없음
    @Around("@annotation(Audited)")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        // 감사 로그 작성
    }
}

@Transactional  // 기본 Order: LOWEST_PRECEDENCE
public void saveOrder(Order order) {
    repository.save(order);
}
```

### 실패 징후
- AOP 실행 순서 비일관
- 트랜잭션 롤백 시 감사 로그 불일치

## DO (베스트 프랙티스)

```java
// Good: 모든 Aspect에 명시적 Order 지정
@Aspect
@Order(1)  // 가장 먼저 실행 (outermost)
public class SecurityAspect { }

@Aspect
@Order(2)
public class AuditAspect { }

// @TransactionalEventListener 활용
@Component
public class AuditEventListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderCreatedEvent event) {
        auditLog.record("Order created: " + event.getOrderId());
    }
}
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| AOP 실행 순서 | 항상 일관 | 비일관 |
| 감사 로그 일관성 | 100% | < 100% |

---

# Nightmare 16: Self-Invocation Mirage

## DON'T (안티패턴)

```java
// Bad: 동일 클래스 내에서 this.method() 호출
@Service
public class UserService {
    public UserDto getUser(Long id) {
        validate(id);
        return this.getCachedUser(id);  // ❌ Self-invocation!
    }

    @Cacheable("users")
    public UserDto getCachedUser(Long id) {
        return userRepository.findById(id);  // 캐시 무시됨!
    }
}
```

### 실패 징후
- Cache Miss on 2nd Call = Yes
- @Transactional 동작 안 함
- AOP 어노테이션 무시

## DO (베스트 프랙티스)

```java
// Good: 캐시 로직을 별도 서비스로 분리
@Service
public class UserService {
    private final UserCacheService cacheService;

    public UserDto getUser(Long id) {
        validate(id);
        return cacheService.getCachedUser(id);  // ✅ 외부 호출
    }
}

@Service
public class UserCacheService {
    @Cacheable("users")
    public UserDto getCachedUser(Long id) {
        return userRepository.findById(id);
    }
}
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| Self-Invocation Count | 0건 | > 0건 |
| Cache Hit on 2nd Call | Yes | No |

---

# Nightmare 17: Poison Pill

## DON'T (안티패턴)

```java
// Bad: 잘못된 메시지로 파이프라인 중단
@KafkaListener(topics = "orders")
public void handleOrder(String message) {
    Order order = objectMapper.readValue(message, Order.class);
    // 잘못된 메시지 포맷 → 파싱 실패 → Consumer 중단
}
```

### 실패 징후
- Consumer 중단
- 메시지 처리 누적
- 파이프라인 정지

## DO (베스트 프랙티스)

```java
// Good: Dead Letter Queue로 잘못된 메시지 격리
@KafkaListener(topics = "orders")
public void handleOrder(String message) {
    try {
        Order order = objectMapper.readValue(message, Order.class);
        processOrder(order);
    } catch (JsonProcessingException e) {
        // 잘못된 메시지를 DLQ로 전송
        deadLetterQueue.send(message);
        log.error("Invalid message sent to DLQ: {}", message, e);
    }
}
```

---

# Nightmare 18: Deep Paging

## DON'T (안티패턴)

```java
// Bad: Offset-based pagination (Deep Cursor 문제)
public List<GameCharacter> findAll(int page, int size) {
    return repository.findAll(
        PageRequest.of(page, size, Sort.by("id").ascending())
    ).getContent();
}

// page=100000, size=100 → 100000001건 skip → 성능 저하
```

### 실패 징후
- 쿼리 응답 시간 > 10초
- Offset 크기에 비례한 성능 저하
- DB 부하 증가

## DO (베스트 프랙티스)

```java
// Good: Cursor-based pagination (Keyset Pagination)
public List<GameCharacter> findAllAfterId(Long lastId, int size) {
    return repository.findByIdAfterOrderByIdAsc(lastId, PageRequest.of(0, size));
}

// 쿼리: SELECT * FROM characters WHERE id > {lastId} ORDER BY id LIMIT {size}
// 성능: O(1) - 마지막 ID부터 상수 시간
```

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| 쿼리 응답 시간 | < 100ms | > 1s |
| Offset 크기 의존성 | No | Yes |

---

# Nightmare 19: Outbox Replay (Plus Compound Scenarios)

## DON'T (안티패턴)

```java
// Bad: API 장애만 검증, 복합 장애 무시
@Test
void testOutboxReplay() {
    // Given: Nexon API 503
    mockApi.simulateError(503);

    // When: Replay 실행
    outboxProcessor.replay();

    // Then: 모두 성공
    assertThat(outboxRepository.count()).isZero();
}
```

### 실패 징후
- 단일 장애에서만 복구 가능
- 복합 장애 시 데이터 유실
- Outbox 상태 불일치

## DO (베스트 프랙티스)

```java
// Good: 복합 장애 시나리오 테스트
// CF-1: N19 + Redis Timeout
@Test
@DisplayName("CF-1: N19 + Redis Timeout - Cache fallback during replay")
void shouldRecoverAfterRedisTimeout() throws Exception {
    // Given: 10K Outbox entries + Nexon API 503 (6h)
    mockApi.simulateError(503);
    createOutboxEntries(10_000);

    // When: API 복구 후 Replay 시작 + Redis timeout 발생
    mockApi恢复正常();
    redisProxy.toxics().latency("redis-latency", ToxicDirection.DOWNSTREAM, 5000);

    // Then: Cache fallback → continue replay → 100% complete
    assertThat(outboxProcessor.replay()).isTrue();
    assertThat(outboxRepository.count()).isZero();
    assertThat(messageLossCount).isZero();
}

// CF-2: N19 + DB Failover
// CF-3: N19 + Process Kill
```

### 성공 기준 (Compound Scenarios)
| Scenario | Message Loss | Completion | Recovery Time | DLQ Rate |
|----------|--------------|------------|---------------|----------|
| CF-1 (Redis) | 0 | 100% | ~5 min | <0.1% |
| CF-2 (DB) | 0 | 100% | ~10 min | <0.1% |
| CF-3 (Process) | 0 | 100% | ~7 min | <0.1% |

### 멱등성 보장
```java
// Good: requestId 기반 중복 방지
public class NexonApiOutboxProcessor {
    @Retryable(maxAttempts = 3)
    public void replay() {
        List<Outbox> pending = outboxRepository.findPendingForReplay(LOCKED, 100);

        for (Outbox entry : pending) {
            try {
                // requestId로 멱등성 확인
                if (apiClient.isAlreadyProcessed(entry.getRequestId())) {
                    entry.markAsCompleted();
                    continue;
                }

                apiClient.call(entry);
                entry.markAsCompleted();
            } catch (Exception e) {
                entry.markAsFailed(e);
            }
        }
    }
}
```

---

## Nightmare 시나리오 실행 가이드

### Quick Start

```bash
# Prerequisites: Docker Compose running (MySQL, Redis)
docker-compose up -d

# Run specific Nightmare test
./gradlew test --tests "maple.expectation.chaos.nightmare.ThunderingHerdNightmareTest" \
  2>&1 | tee logs/nightmare-01-$(date +%Y%m%d_%H%M%S).log
```

### 테스트 환경

| Parameter | Value |
|-----------|-------|
| Java Version | 21 |
| Spring Boot | 3.5.4 |
| MySQL | 8.0 (Docker) |
| Redis | 7.x (Docker) |
| Concurrent Requests | 1,000 |
| Thread Pool | 100 |

---

## 관련 문서

- [docs/02_Chaos_Engineering/06_Nightmare/Scenarios/](../../02_Chaos_Engineering/06_Nightmare/Scenarios/) - 전체 Nightmare 시나리오
- [chaos-engineering.md](chaos-engineering.md) - Chaos Engineering 전략
- [testing-guide.md](../../03_Technical_Guides/testing-guide.md) - 테스트 작성 가이드
