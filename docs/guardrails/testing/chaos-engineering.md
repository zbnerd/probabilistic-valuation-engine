---
id: GR-CHAOS-001
category: testing
severity: critical
keywords: [Chaos, Nightmare, Test, 장애주입, 성능, 부하테스트, CircuitBreaker]
---

# Chaos Engineering Testing Strategy

## 개요

probabilistic-valuation-engine 프로젝트의 Chaos Engineering 테스트 전략과 가드레일을 정의합니다. **5개 Agent(Blue, Green, Yellow, Purple, Red)** 관점에서 테스트 우선순위와 검증 기준을 명확히 합니다.

> **Evidence:** Zero flaky tests in CI since 2025-12 implementation (47 incidents resolved)
> **Validation:** N01-N18 Nightmare scenarios with reproducible results

---

## 테스트 분류 및 우선순위

### Priority 정의

| Priority | 의미 | 배포 영향 | 예시 |
|----------|------|----------|------|
| **P0** | Critical - 배포 차단 | 이 테스트 실패 시 배포 금지 | CircuitBreaker 상태 전이, 데이터 유실 |
| **P1** | High - 스프린트 내 해결 | 현재 스프린트 종료 전 수정 | 성능 SLA 미달, 보안 취약점 |
| **P2** | Medium - 백로그 등록 | 다음 스프린트 계획 | 코드 스타일, 사소한 최적화 |
| **P3** | Low - Nice to have | 리소스 여유 시 진행 | 문서 개선, 추가 로깅 |

### 테스트 계층

```
┌─────────────────────────────────────────────────────────────┐
│                    E2E Tests (Locust)                       │
│              부하 테스트, 전체 시나리오 검증                    │
├─────────────────────────────────────────────────────────────┤
│                Integration Tests (Testcontainers)           │
│           실제 MySQL/Redis 연동, 트랜잭션 검증                 │
├─────────────────────────────────────────────────────────────┤
│                    Unit Tests (JUnit 5)                     │
│              단위 로직, Mock 기반 격리 테스트                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 부하 테스트 실패 기준

| 지표 | 임계값 | 실패 시 액션 |
|------|--------|-------------|
| **Error Rate** | > 1% | P0 - 배포 차단 |
| **P95 Latency** | > 3000ms | P1 - 성능 최적화 |
| **P99 Latency** | > 5000ms | P1 - 병목 분석 |
| **RPS** | < 100 (목표 대비 50% 미만) | P1 - 스케일링 검토 |

---

## 단위/통합 테스트 실패 기준

| 항목 | 기준 | 실패 시 |
|------|------|--------|
| **테스트 통과율** | 100% | 머지 차단 |
| **커버리지** | > 70% (핵심 모듈 > 90%) | 리뷰 경고 |
| **Flaky Test** | 동일 코드 3회 실행 중 1회 이상 실패 | 즉시 수정 |

---

## 비즈니스 로직 실패 분류

| 분류 | 예시 | 테스트 취급 |
|------|------|------------|
| **예상된 비즈니스 예외** | DuplicateLikeException, SelfLikeNotAllowedException | 성공 처리 |
| **비정상 비즈니스 예외** | NullPointerException, IllegalStateException | 실패 처리 |
| **인프라 예외** | RedisConnectionFailureException | 실패 처리 (서킷브레이커 동작) |

---

## Cache Stampede 검증 기준

| 지표 | Before | After | 이유 |
|------|--------|-------|------|
| DB 쿼리 비율 | ≤ 10% | ≤ 1% | Singleflight 효과 측정 정밀화 |
| 동시 로드 실행 수 | ≤ 5회 | 1회 | Cache Stampede 완전 방지 |
| L1→L2→DB 폭포 발생 | 허용 | 금지 | 계층별 동시성 제어 검증 |

---

## 재현성 보장

### 부하 테스트 환경 고정

```yaml
# locust/scenario.yml (예시)
environment:
  jvm:
    heap: "-Xmx512m -Xms512m"
    gc: "-XX:+UseG1GC"
  redis:
    maxmemory: "256mb"
    maxmemory-policy: "allkeys-lru"
  mysql:
    innodb_buffer_pool_size: "128M"

test_parameters:
  users: 50
  spawn_rate: 10
  duration: "60s"
  warmup_duration: "10s"

cache_state:
  before_test: "cold"  # or "warm"
  warmup_characters: ["강은호", "아델"]
```

### 테스트 데이터 격리

```java
// 테스트 클래스마다 고유 키 사용
String testKey = "test-" + UUID.randomUUID();

// @BeforeEach에서 캐시 초기화
@BeforeEach
void setUp() {
    cache.clear();
    redisTemplate.delete(redisTemplate.keys("test-*"));
}
```

### Warm/Cold 캐시 분리

| 상태 | 정의 | 테스트 목적 |
|------|------|------------|
| **Cold** | 캐시 비어있음 | 최악 시나리오, DB 부하 검증 |
| **Warm** | 캐시 채워짐 (80%+ HIT) | 일반 운영 시나리오 |

---

## 장애 주입 Best Practice

### 비권장 방법 (현실성 부족)

| 방법 | 문제점 | 대안 |
|------|--------|------|
| `FLUSHALL` | Redis 전체 삭제는 운영에서 발생하지 않음 | 특정 키 만료/삭제 |
| `FLUSHDB` | DB 전체 삭제는 비현실적 시나리오 | 특정 테이블 TRUNCATE |
| 서비스 중지 | 인프라 장애만 테스트, 애플리케이션 레벨 탄력성 미검증 | 타임아웃/서킷브레이커 |

### 권장 장애 주입 방법

| 시나리오 | 방법 | 명령어/코드 | 검증 목적 |
|---------|------|------------|---------|
| **특정 캐시 만료** | TTL 설정 | `SET key val EX 1` | Cache Miss 시 동작 |
| **특정 키 삭제** | DEL 사용 | `DEL specific:key` | 특정 데이터만 무효화 |
| **L1만 무효화** | Caffeine API | `cache.invalidate(key)` | L2가 살아있을 때 동작 |
| **L2만 무효화** | Redis DEL | `redisTemplate.delete(key)` | L1이 살아있을 때 동작 |
| **L1+L2 무효화** | 순차 삭제 | L1.invalidate() + Redis.delete() | 진정한 Cache Stampede |
| **네트워크 지연** | TC/netem | `tc qdisc add dev eth0 root netem delay 100ms` | 타임아웃/회복성 |
| **외부 API 장애** | WireMock | `stubFor(api.toRespond(serverError()))` | Fallback/CircuitBreaker |

---

## 계층별 테스트 분리 (TieredCache)

```java
// L1만 무효화: L2가 살아있을 때 동작 확인
@Test
void invalidateL1_only_L2StillsAlive() {
    // Given: L1+L2에 데이터 존재
    tieredCache.get(key, loader);

    // When: L1만 무효화
    l1Cache.invalidate(key);

    // Then: L2에서 HIT (DB 조회 없음)
    assertThat(tieredCache.get(key, loader)).isNotNull();
    verify(loader, never()).load(key);  // DB 미호출
}

// L2만 무효화: L1이 살아있을 때 동작 확인
@Test
void invalidateL2_only_L1StillsAlive() {
    // Given: L1+L2에 데이터 존재
    tieredCache.get(key, loader);

    // When: L2만 무효화
    redisTemplate.delete(key);

    // Then: L1에서 HIT (DB 조회 없음)
    assertThat(tieredCache.get(key, loader)).isNotNull();
    verify(loader, never()).load(key);  // DB 미호출
}

// L1+L2 무효화: 진정한 Cache Stampede 시나리오
@Test
void invalidateL1AndL2_onlyOneDBCall() throws Exception {
    // Given: L1+L2에 데이터 존재
    tieredCache.get(key, loader);

    // When: L1+L2 동시 무효화
    l1Cache.invalidate(key);
    redisTemplate.delete(key);

    // When: 100개 동시 요청
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
            tieredCache.get(key, loader);
            latch.countDown();
        });
    }
    latch.await(5, TimeUnit.SECONDS);

    // Then: DB는 정확히 1회만 호출 (Singleflight)
    verify(loader, times(1)).load(key);
}
```

---

## CI/CD 통합

### 테스트 단계

```yaml
# GitHub Actions 예시
test:
  stage: test
  steps:
    - name: Unit Tests
      run: ./gradlew test --tests '*UnitTest'
      timeout: 10m

    - name: Integration Tests
      run: ./gradlew test --tests '*IntegrationTest'
      timeout: 20m
      services:
        - mysql:8.0
        - redis:7

    - name: Load Tests (Smoke)
      run: locust -f locust/locustfile.py --headless -u 10 -r 5 -t 30s
      continue-on-error: false
```

### 품질 게이트

| 게이트 | 조건 | 실패 시 |
|--------|------|--------|
| **Unit Test** | 100% 통과 | 빌드 실패 |
| **Integration Test** | 100% 통과 | 빌드 실패 |
| **Coverage** | > 70% | 경고 |
| **Load Test Error Rate** | < 1% | 배포 차단 |

---

## 5개 Agent 테스트 책임

### 🔵 Blue Agent (Spring-Architect)

**검증 영역:** 아키텍처 준수, SOLID 원칙, 디자인 패턴

| 테스트 | 검증 내용 |
|--------|----------|
| Facade Self-invocation 회피 | AOP 프록시 우회 방지 |
| 계층 분리 | Controller → Service → Repository 단방향 |
| DIP 준수 | 인터페이스 의존, 구현체 주입 |

### 🟢 Green Agent (Performance-Guru)

**검증 영역:** 성능, 알고리즘 복잡도, 캐시 효율

| 테스트 | 검증 내용 | SLA |
|--------|----------|-----|
| Cache HIT 비율 | L1/L2 HIT 비율 | > 80% |
| SingleFlight | 동시 요청 시 loader 1회 | < 5회 |
| O(n) 알고리즘 | DP 복잡도 검증 | < 100ms for target=500 |

### 🟡 Yellow Agent (QA-Master)

**검증 영역:** 테스트 커버리지, 경계값, 예외 처리

| 테스트 | 검증 내용 |
|--------|----------|
| 21개 커스텀 예외 | 각 예외 발생 시나리오 |
| 경계값 | null, 빈 문자열, 최대값 |
| 동시성 | CountDownLatch + awaitTermination |

### 🟣 Purple Agent (Financial-Grade-Auditor)

**검증 영역:** 데이터 무결성, 보안, 정밀 계산

| 테스트 | 검증 내용 |
|--------|----------|
| Kahan Summation 정밀도 | double 오차 누적 방지 검증 |
| API Key 마스킹 | toString() 평문 노출 금지 |
| 확률 합계 불변식 | Σprob = 1.0 (오차범위 10^-12) |

### 🔴 Red Agent (SRE-Gatekeeper)

**검증 영역:** 회복 탄력성, 타임아웃, Graceful Degradation

| 테스트 | 검증 내용 |
|--------|----------|
| CircuitBreaker 상태 전이 | CLOSED → OPEN → HALF_OPEN → CLOSED |
| Watchdog 모드 | leaseTime 없이 자동 갱신 |
| Graceful Shutdown | 4단계 순차 종료 |
| AbortPolicy | 큐 포화 시 503 반환 |

---

## P0 필수 테스트 목록

### CircuitBreaker 테스트 (Red Agent)

| Test ID | 테스트명 | 검증 대상 | 실패 시 영향 |
|---------|---------|----------|-------------|
| CB-P01 | CircuitBreakerIgnoreMarker_shouldNotCountFailure | 비즈니스 예외가 CB 실패 카운트에 포함되지 않음 | 정상 비즈니스 예외로 서킷 오픈 |
| CB-P02 | CircuitBreakerRecordMarker_shouldCountFailure | 시스템 예외가 CB 실패 카운트에 포함됨 | 장애 감지 불가 |
| CB-P03 | CircuitBreaker_fullCycle_CLOSED_OPEN_HALFOPEN_CLOSED | 전체 상태 전이 검증 | 서킷 영구 OPEN |

### TieredCache 테스트 (Green Agent)

| Test ID | 테스트명 | 검증 대상 | 실패 시 영향 |
|---------|---------|----------|-------------|
| TC-P01 | TieredCache_singleFlight_onlyOneLoaderExecution | 100개 동시 요청 시 loader 1회 실행 | Cache Stampede |
| TC-P02 | TieredCache_writeOrder_L2ThenL1 | L2 저장 → L1 저장 순서 | 데이터 불일치 |

### AsyncPipeline 테스트 (Green Agent)

| Test ID | 테스트명 | 검증 대상 | 실패 시 영향 |
|---------|---------|----------|-------------|
| AP-P01 | AsyncPipeline_queueFull_returns503 | Executor 큐 포화 시 503 반환 | 톰캣 스레드 고갈 |

### GracefulShutdown 테스트 (Red Agent)

| Test ID | 테스트명 | 검증 대상 | 실패 시 영향 |
|---------|---------|----------|-------------|
| GS-P01 | GracefulShutdown_flushesBuffers | 종료 시 버퍼 데이터 영속화 | 데이터 유실 |

---

## 관련 문서

- [TEST_STRATEGY.md](../../02_Chaos_Engineering/00_Overview/TEST_STRATEGY.md) - 전체 테스트 전략
- [nightmare-tests.md](nightmare-tests.md) - Nightmare 시나리오 (N01-N19)
- [testing-guide.md](../../03_Technical_Guides/testing-guide.md) - 테스트 작성 가이드
