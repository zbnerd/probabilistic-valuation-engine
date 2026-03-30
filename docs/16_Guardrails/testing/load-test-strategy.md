---
id: GR-TEST-005
category: testing
severity: critical
keywords: [load test, performance, wrk, Locust, RPS, latency, error rate, CI/CD]
languages: [java, kotlin, python, yaml, bash]
---

# Load Test Strategy

## 개요

probabilistic-valuation-engine 프로젝트의 부하 테스트(Load Test) 전략과 실패 기준을 정의합니다. **성능 회귀**을 조기에 발견하고 **배포 차단** 기준을 명확히 합니다.

> **Evidence:** 5개 Phase 부하 테스트 (V4 L1 Fast Path, V4 Write-Behind, V4 ADR Refactoring, V5 Stateless, Multi-Instance Warmup)
>
> **Result:** RPS 241 → 688 (+185%), Error Rate 3.3% → 0% (-100%)

---

## 부하 테스트 실패 기준

| 지표 | 임계값 | 실패 시 액션 | 우선순위 |
|------|--------|-------------|----------|
| **Error Rate** | > 1% | P0 - 배포 차단 | Critical |
| **P95 Latency** | > 3000ms | P1 - 성능 최적화 | High |
| **P99 Latency** | > 5000ms | P1 - 병목 분석 | High |
| **RPS** | < 50% 목표 대비 | P1 - 스케일링 검토 | High |

---

## DON'T (안티패턴)

### 1. 환경 차이 무시

```yaml
# Bad - 로컬과 CI 환경이 다름
environment:
  jvm:
    heap: "-Xmx8g"  # CI는 2G
  redis:
    maxmemory: "4gb"  # CI는 256mb
```

**문제점:** 로컬에서 통과해도 CI에서 실패

### 2. 샘플 크기 부족

```bash
# Bad - 1분만 실행
wrk -t 4 -c 100 -d 1m http://localhost:8080/api/characters
```

**문제점:** 10,000 요청 미만으로 통계적 유의성 부족

### 3. 장애 주입 비현실성

```bash
# Bad - 전체 Redis 삭제
redis-cli FLUSHALL
```

**문제점:** 운영에서 발생하지 않는 시나리오

### 4. 캐시 상태 미정의

```java
// Bad - 캐시 상태不明
@Test
void loadTest() {
    // Cold? Warm?
    for (int i = 0; i < 1000; i++) {
        client.get("/api/characters/강은호");
    }
}
```

### 5. 아웃라이어 미처리

```java
// Bad - 아웃라이어 포함된 평균
double avgLatency = allLatencies.stream()
    .mapToDouble(Long::longValue)
    .average()
    .orElse(0);  // p99/p50으로 검증 안 함
```

---

## DO (베스트 프랙티스)

### 1. 환경 고정

```yaml
# Good - 환경 고정
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

### 2. 충분한 샘플 크기

```bash
# Good - 최소 10,000 요청
wrk -t 4 -c 100 -d 30s \
    http://localhost:8080/api/characters
# RPS 300 × 30초 = 9,000 (약간 부족, 60초 권장)
```

### 3. 현실적인 장애 주입

| 시나리오 | 방법 | 명령어/코드 | 검증 목적 |
|---------|------|------------|---------|
| **특정 캐시 만료** | TTL 설정 | `SET key val EX 1` | Cache Miss 시 동작 |
| **특정 키 삭제** | DEL 사용 | `DEL specific:key` | 특정 데이터만 무효화 |
| **L1만 무효화** | Caffeine API | `cache.invalidate(key)` | L2가 살아있을 때 동작 |
| **L2만 무효화** | Redis DEL | `redisTemplate.delete(key)` | L1이 살아있을 때 동작 |
| **L1+L2 무효화** | 순차 삭제 | L1.invalidate() + Redis.delete() | 진정한 Cache Stampede |
| **네트워크 지연** | TC/netem | `tc qdisc add dev eth0 root netem delay 100ms` | 타임아웃/회복성 |
| **외부 API 장애** | WireMock | `stubFor(api.toRespond(serverError()))` | Fallback/CircuitBreaker |

### 4. 캐시 상태 명시

```java
// Good - Cold Cache 테스트
@Test
@DisplayName("Cold Cache: DB 직접 조회")
void coldCacheLoadTest() {
    // Given: 캐시 비어있음
    cache.clear();
    redisTemplate.delete(redisTemplate.keys("*"));

    // When: 100개 동시 요청
    // Then: DB Query Rate ≤ 10% (Singleflight 효과)
}

// Good - Warm Cache 테스트
@Test
@DisplayName("Warm Cache: L1/L2 HIT")
void warmCacheLoadTest() {
    // Given: 캐시 미리 채움 (80%+ HIT)
    warmupCache(List.of("강은호", "아델", ...));

    // When: 부하 테스트
    // Then: Cache HIT Rate > 80%, p99 < 100ms
}
```

### 5. 아웃라이어 처리

```java
// Good - P99/P50 비율로 아웃라이어 감지
@Test
void loadTestWithOutlierDetection() {
    List<Long> latencies = executeLoadTest();

    double p50 = percentile(latencies, 50);
    double p99 = percentile(latencies, 99);
    double ratio = p99 / p50;

    // p99/p50 < 3: 건강 (p99/p50 ratio: 2.25 ✅)
    // p99/p50 > 5: 아웃라이어 의심
    assertThat(ratio).isLessThan(3.0);

    // Max < 2× p99: 극단적 아웃라이어 없음
    long max = Collections.max(latencies);
    assertThat(max).isLessThan((long) (2 * p99));
}
```

---

## TieredCache 계층별 테스트

### L1만 무효화: L2가 살아있을 때

```java
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
```

### L2만 무효화: L1이 살아있을 때

```java
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
```

### L1+L2 무효화: 진정한 Cache Stampede

```java
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
# locust/scenario.yml
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

## P0 필수 부하 테스트

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

## Performance Evidence

### V4 Phase 2 (L1 Fast Path)

| 지표 | wrk | Locust | 비고 |
|------|-----|-------|------|
| RPS | 555 | 241 | Locust GIL 병목 (43%) |
| L1 Hit Rate | 99.99% | - | L1 Fast Path 성공 |
| Error Rate | 3.3% (600c) | - | 최적 연결 수: 600 |
| p99/p50 Ratio | 1.98 | - | Excellent |

### V4 Parallel Write-Behind

| 지표 | Before | After | 개선 |
|------|--------|-------|------|
| RPS | 555 | 674 | +21% |
| Error Rate | 1.4-3.3% | 0% | -100% |
| Preset Time | 300ms | 100ms | 3x |
| DB Write Time | 15-30ms | 0.1ms | 150-300x |

### V5 Stateless (Multi-Instance)

| 지표 | V4 Single | V5 Single | V5 Multi (5) | 비고 |
|------|-----------|-----------|--------------|------|
| RPS | 688 | 324 | 1350 | Scale-out 성공 |
| Data Consistency | - | - | 100% | MD5 hash 일치 |
| Redis Ops/sec | 98 | 30 | 150 | Bottleneck 아님 |

---

## Load Test 실행 가이드

### Quick Start

```bash
# Prerequisites: Docker Compose running
docker-compose up -d

# Run wrk load test
wrk -t 4 -c 100 -d 60s http://localhost:8080/api/characters/강은호

# Run Locust load test
locust -f locust/locustfile.py --headless -u 50 -r 10 -t 60s
```

### 테스트 환경

| Parameter | Value |
|-----------|-------|
| Java Version | 21 |
| Spring Boot | 3.5.4 |
| MySQL | 8.0 (Docker) |
| Redis | 7.x (Docker) |
| Concurrent Requests | 50-1000 |
| Thread Pool | 100 |
| Test Duration | 30-60 seconds |
| Warmup Duration | 10 seconds |

---

## 관련 문서

- [chaos-engineering.md](chaos-engineering.md) - Chaos Engineering 전략
- [nightmare-tests.md](nightmare-tests.md) - Nightmare 시나리오 (N01-N19)
- [testing-guide.md](../../03_Technical_Guides/testing-guide.md) - 테스트 작성 가이드
- [LOAD_TEST_REPORT_FIXES_SUMMARY.md](../../05_Reports/05_06_Load_Tests/LOAD_TEST_REPORT_FIXES_SUMMARY.md) - 부하 테스트 리포트 요약
