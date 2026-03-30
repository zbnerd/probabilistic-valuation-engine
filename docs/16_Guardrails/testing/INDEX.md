# Guardrails - Testing

## 개요

테스트 전략, 플래키 테스트 방지, 동시성 테스트, Chaos Engineering 시나리오에 관한 가드레일입니다.

> **Evidence:** Zero flaky tests in CI since 2025-12 implementation (47 incidents resolved to zero flaky rate)

---

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-TEST-001 | [Unit Test Best Practices](unit-test.md) | critical | Thread.sleep, Awaitility, @DirtiesContext, test isolation |
| GR-TEST-002 | [Flaky Test Prevention](flaky-test-prevention.md) | critical | Flaky Test, @Tag("flaky"), quarantine, determinism |
| GR-TEST-003 | [Concurrency Test Best Practices](concurrency-test.md) | critical | ExecutorService, awaitTermination, CountDownLatch |
| GR-TEST-004 | [Testcontainers Singleton Pattern](testcontainers-singleton.md) | critical | Testcontainers, Singleton, @Testcontainers, data isolation |
| GR-TEST-005 | [Load Test Strategy](load-test-strategy.md) | critical | load test, performance, wrk, Locust, RPS, latency |
| GR-CHAOS-001 | [Chaos Engineering Strategy](chaos-engineering.md) | critical | Chaos, Nightmare, Test, 장애주입, 성능 |
| GR-NIGHTMARE-001 | [Nightmare Scenarios (N01-N19)](nightmare-tests.md) | critical | Nightmare, Cache Stampede, Deadlock, Timeout |

## Chaos Test Nightmare Scenarios (N01-N19)

### P0 Critical (N01-N10)

| ID | Title | Severity | Keywords |
|----|-------|----------|----------|
| N01 | [SingleFlight Race Condition](chaos/N01-singleflight-race-condition.md) | Critical | Thundering Herd, SingleFlight, Race Condition |
| N02 | [Deadlock Trap](chaos/N02-deadlock-trap.md) | Critical | Deadlock, Named Lock, Lock Ordering |
| N03 | [Thread Pool Exhaustion](chaos/N03-thread-pool-exhaustion.md) | Critical | ThreadPool, Async, Virtual Threads |
| N04 | [Connection Vampire](chaos/N04-connection-vampire.md) | Critical | Connection Pool, Transactional Boundary, HikariCP |
| N05 | [Celebrity Problem](chaos/N05-celebrity-problem.md) | Critical | Hot Key, Cache Stampede, Redis |
| N06 | [Timeout Cascade](chaos/N06-timeout-cascade.md) | Critical | Timeout, Zombie Request, Timeout Hierarchy |
| N07 | [Metadata Lock Freeze](chaos/N07-metadata-lock-freeze.md) | Critical | MDL, MySQL DDL, Metadata Lock |
| N08 | [Redis Death Thundering Herd](chaos/N08-redis-death-thundering-herd.md) | Critical | Redis Death, Fallback, Connection Pool |
| N09 | [Circular Lock Deadlock](chaos/N09-circular-lock-deadlock.md) | Critical | Circular Lock, Coffman Conditions, Deadlock |
| N10 | [CallerRunsPolicy Betrayal](chaos/N10-caller-runs-policy.md) | Critical | CallerRunsPolicy, ThreadPool, Async, Backpressure |

### P1 High (N11-N14)

| ID | Title | Severity | Keywords |
|----|-------|----------|----------|
| N11 | [Lock Fallback Avalanche](chaos/N11-lock-fallback-avalanche.md) | High | Lock Fallback, HikariCP, Connection Pool, MySQL Named Lock |
| N12 | [Async Context Loss](chaos/N12-async-context-loss.md) | High | Async Context Loss, MDC, ThreadLocal, TaskDecorator |
| N13 | [Zombie Outbox](chaos/N13-zombie-outbox.md) | High | Zombie Outbox, Outbox Pattern, JVM Crash, Stale Recovery |
| N14 | [Pipeline Exception](chaos/N14-pipeline-exception.md) | High | Pipeline Exception, LogicExecutor, Silent Failure, Exception Swallowing |

### P2 Medium (N15-N19)

| ID | Title | Severity | Keywords |
|----|-------|----------|----------|
| N15 | [AOP Order Problem](chaos/N15-aop-order-problem.md) | Medium | AOP Order, @Order, TransactionalEventListener, Transaction Boundary |
| N16 | [Self-Invocation Mirage](chaos/N16-self-invocation.md) | Medium | Self-Invocation, AOP Proxy, @Cacheable, @Transactional |
| N17 | [Poison Pill](chaos/N17-poison-pill.md) | Medium | Poison Pill, DLQ, Dead Letter Queue, Head-of-Line Blocking, ContentHash |
| N18 | [Deep Paging Abyss](chaos/N18-deep-paging.md) | Medium | Deep Paging, OFFSET, Cursor Pagination, Keyset Pagination |
| N19 | [Outbox Replay Flood](chaos/N19-outbox-replay.md) | Critical | Outbox Replay, Transactional Outbox, External API Outage, Replay Throughput |

---

## 주요 주제

### Unit Test Best Practices
- **Awaitility 사용**: `Thread.sleep()` 금지
- **결정성 확보**: Clock 주입, ID 생성기 주입
- **테스트 격리**: `@BeforeEach`로 상태 초기화
- **Testcontainers**: Docker 기반 격리된 환경

### Flaky Test Prevention
- **5대 원칙**: Determinism, Isolation, Independence, Explicit Synchronization, Observability
- **6대 근본 원인**: 시간 의존성, 순서 의존성, 외부 의존성, 환경 차이, 공유 상태, 무작위성
- **Quarantine 절차**: @Tag("flaky")로 격리 후 GitHub 이슈 생성

### Concurrency Test
- **shutdown() + awaitTermination()**: 필수 조합
- **@Transactional 제거**: 다른 스레드에서 안 보임
- **CountDownLatch**: 명시적 동기화
- **낙관적/비관적 락**: Race Condition 방지

### Testcontainers Singleton Pattern
- **컨테이너 공유, 데이터 격리**: JVM 동안 컨테이너 1회, 테스트마다 데이터 리셋
- **static final + Startables.deepStart()**: JUnit Extension 사용 안 함
- **TRUNCATE + FLUSHDB**: 매 테스트마다 상태 초기화
- **waitingFor() 명시**: 컨테이너 준비 완료 대기

### Load Test Strategy
- **부하 테스트 기준**: Error Rate < 1%, P99 < 5000ms, RPS 목표 대비 50% 이상
- **환경 고정**: JVM heap, GC, Redis maxmemory, MySQL buffer pool
- **Cache Stampede 검증**: DB Query Rate ≤ 1%, Singleflight 1회 실행
- **TieredCache 계층별 테스트**: L1/L2 분리 무효화 검증

### Chaos Engineering
- **부하 테스트 기준**: Error Rate < 1%, P99 < 5000ms
- **5개 Agent 책임**: Blue, Green, Yellow, Purple, Red
- **장애 주입 Best Practice**: FLUSHALL 대신 특정 키 삭제/만료
- **TieredCache 테스트**: L1/L2 계층별 분리 검증

### Nightmare 시나리오
19개의 운영 환경 기반 장애 시나리오를 정의하고 검증합니다:

| ID | 시나리오 | 문제 | 해결 | 난이도 |
|----|---------|------|------|--------|
| N01 | Thundering Herd | Cache Stampede | Singleflight | P0 |
| N02 | Deadlock Trap | Circular Lock | Lock Ordering | P0 |
| N03 | Thread Pool Exhaustion | CallerRunsPolicy | AbortPolicy | P0 |
| N04 | Connection Vampire | 트랜잭션 내 API 호출 | API 호출 분리 | P0 |
| N05 | Celebrity Problem | Hot Key 경합 | Singleflight + Sharding | P1 |
| N06 | Timeout Cascade | 타임아웃 계층 불일치 | 계층 정렬 | P0 |
| N07-N19 | Additional Scenarios | 다양한 장애 패턴 | 각각의 대응책 | P0-P2 |

---

## 테스트 우선순위 (Priority)

| Priority | 의미 | 배포 영향 | 예시 |
|----------|------|----------|------|
| **P0** | Critical - 배포 차단 | 이 테스트 실패 시 배포 금지 | CircuitBreaker 상태 전이, 데이터 유실 |
| **P1** | High - 스프린트 내 해결 | 현재 스프린트 종료 전 수정 | 성능 SLA 미달, 보안 취약점 |
| **P2** | Medium - 백로그 등록 | 다음 스프린트 계획 | 코드 스타일, 사소한 최적화 |

---

## P0 필수 테스트 목록

### CircuitBreaker 테스트 (Red Agent)
- CB-P01: CircuitBreakerIgnoreMarker_shouldNotCountFailure
- CB-P02: CircuitBreakerRecordMarker_shouldCountFailure
- CB-P03: CircuitBreaker_fullCycle_CLOSED_OPEN_HALFOPEN_CLOSED

### TieredCache 테스트 (Green Agent)
- TC-P01: TieredCache_singleFlight_onlyOneLoaderExecution
- TC-P02: TieredCache_writeOrder_L2ThenL1

### AsyncPipeline 테스트 (Green Agent)
- AP-P01: AsyncPipeline_queueFull_returns503

### GracefulShutdown 테스트 (Red Agent)
- GS-P01: GracefulShutdown_flushesBuffers

---

## 관련 문서

### 상위 문서
- [CLAUDE.md](../../../CLAUDE.md) - 프로젝트 핵심 규칙

### 원본 문서
- [docs/02_Chaos_Engineering/](../02_Chaos_Engineering/) - 전체 Chaos Engineering 문서
- [docs/03_Technical_Guides/testing-guide.md](../03_Technical_Guides/testing-guide.md) - 테스트 가이드
- [docs/03_Technical_Guides/flaky-test-management.md](../03_Technical_Guides/flaky-test-management.md) - Flaky Test 관리

### 관련 Guardrails
- [../backend/spring/logic-executor.md](../backend/spring/logic-executor.md) - LogicExecutor 패턴 (예외 처리와 관련)
- [../database/transaction/transactional-boundaries.md](../database/transaction/transactional-boundaries.md) - 트랜잭션 경계 (동시성 테스트 관련)

---

## 빠른 참조

### Flaky Test 즉시 격리
```java
@Test
@Tag("flaky")  // CI에서 제외
@DisplayName("동시성 테스트")
void concurrencyTest() { }
```

### 동시성 테스트 필수 패턴
```java
executor.shutdown();
executor.awaitTermination(5, TimeUnit.SECONDS);
```

### 장애 주입 권장 방법
```bash
# 시나리오 A: 특정 키만 삭제
redis-cli DEL nightmare:test:key

# 시나리오 B: TTL 자연 만료
redis-cli SET nightmare:test:key "value" EX 1 && sleep 1

# 시나리오 C: L1/L2 계층별 선택적 무효화
# L1만: Caffeine.clear() 후 Redis 유지
# L2만: redis-cli DEL 후 Caffeine 유지
```

### Testcontainers Singleton 패턴
```java
// 공유 컨테이너
public final class SharedContainers {
    public static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
    static {
        Startables.deepStart(Stream.of(MYSQL)).join();  // 직접 시작
    }
}

// 데이터 격리
@BeforeEach
void resetState() {
    redisTemplate.getConnectionFactory().getConnection().flushDb();
    jdbc.execute("TRUNCATE TABLE `table_name`");
}
```

---

*Updated: 2026-02-25*
*Version: 1.1.0* (GR-TEST-004, GR-TEST-005 추가)
