---
id: GR-TEST-002
category: testing
severity: critical
keywords: [Flaky Test, @Tag("flaky"), quarantine, determinism, isolation, test independence]
---

# Flaky Test Prevention

## 개요

**Flaky Test(플래키 테스트)**는 코드 변경 없이도 실행 시마다 성공/실패가 번갈아 가며 나타나는 테스트입니다. CI/CD 신뢰도를 떨어뜨리고 개발 생산성을 저하시키므로 반드시 근본 원인을 파악하여 제거해야 합니다.

> **"Flaky Test는 엄밀히 말하면 버그입니다. 특히 동시성 부분에서 생긴다면 그건 진짜 Race Condition 버그입니다."**

---

## Flaky Test 5대 원칙

```
+-------------------------------------------------------------+
|  1. Determinism (결정성)                                     |
|     -> 같은 입력 = 같은 출력 (시간, 랜덤 주입)                 |
+-------------------------------------------------------------+
|  2. Isolation (격리)                                         |
|     -> 테스트 간 상태 공유 금지 (@BeforeEach)                  |
+-------------------------------------------------------------+
|  3. Independence (독립성)                                    |
|     -> 실행 순서에 관계없이 동일 결과                          |
+-------------------------------------------------------------+
|  4. Explicit Synchronization (명시적 동기화)                 |
|     -> Thread.sleep() 금지, CountDownLatch/Awaitility 사용    |
+-------------------------------------------------------------+
|  5. Observability (관찰 가능성)                              |
|     -> 실패 시 충분한 로그/스택트레이스 확보                   |
+-------------------------------------------------------------+
```

---

## Flaky Test 6대 근본 원인

| 원인 | 설명 | 해결책 |
|------|------|--------|
| **1. 시간 의존성** | `LocalDate.now()`, 타임아웃 기반 | Clock 주입 |
| **2. 순서 의존성** | `CompletableFuture`, 멀티스레드 | `CountDownLatch`, `awaitTermination()` |
| **3. 외부 의존성** | Redis 연결, HTTP 호출 | Testcontainers, Mock |
| **4. 환경 차이** | 로컬 vs CI CPU/메모리 | `@EnabledIf`, 프로파일 분리 |
| **5. 공유 상태** | static 변수, 싱글톤 | `@BeforeEach` 초기화 |
| **6. 무작위성** | `Math.random()`, UUID | ID 생성기 주입 |

---

## DON'T (안티패턴)

```java
// 1. 비동기 작업 완료 전 검증
@Test
void testConcurrentAccess() {
    executor.submit(() -> service.process());
    assertEquals(expected, repository.findAll().size());  // ❌ Race Condition
}

// 2. @DirtiesContext 남용 - 컨텍스트 리로드 비용
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class EveryTestNeedsFreshContext { }  // ❌ 느림

// 3. Testcontainers 없이 실제 외부 의존성 사용
@Test
void testWithRealRedis() {
    redisTemplate.opsForValue().set("key", "value");  // ❌ 네트워크 불안정
}

// 4. 플래키 테스트를 @Disabled만 함
@Disabled  // ❌ 왜 플래키한지 문서화 안 함
void flakyTest() { }

// 5. @Transactional 테스트에서 다른 스레드 사용
@Test
@Transactional  // ❌ 트랜잭션 커밋 전까지 다른 스레드에서 안 보임
void concurrencyTest() {
    Member guest = saveAndFlush(Member.createGuest(1000L));
    executorService.submit(() -> donationService.sendCoffee(...));
}
```

---

## DO (베스트 프랙티스)

### 1. 명시적 동기화 - CountDownLatch + awaitTermination

```java
@Test
void testConcurrentAccess() {
    int taskCount = 100;
    ExecutorService executor = Executors.newFixedThreadPool(16);
    CountDownLatch latch = new CountDownLatch(taskCount);

    for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
            try {
                service.process();
            } finally {
                latch.countDown();
            }
        });
    }

    // Step 1: 모든 작업 완료 대기
    latch.await(10, TimeUnit.SECONDS);

    // Step 2: Executor 종료 및 완료 대기
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    // Step 3: 결과 검증
    assertEquals(expected, repository.findAll().size());
}
```

### 2. @BeforeEach로 상태 격리

```java
@BeforeEach
void setUp() {
    repository.deleteAll();
    cacheManager.getCache("myCache").clear();
}
```

### 3. Testcontainers로 격리된 환경

```java
@Testcontainers
class RedisIntegrationTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", redis::getFirstMappedPort);
    }
}
```

### 4. 플래키 테스트 격리 + GitHub 이슈 생성

```java
@Test
@Tag("flaky")  // 격리
@DisplayName("동시성 테스트")
void concurrencyTest() { }
```

```bash
# GitHub 이슈 생성
gh issue create \
  --title "[Flaky Test] DonationTest 동시성 테스트 Race Condition" \
  --label "bug,concurrency,priority:high" \
  --body "@docs/03_Technical_Guides/flaky-test-management.md"
```

### 5. 낙관적 락 (Optimistic Locking)

```java
@Entity
public class Member {
    @Version  // JPA 낙관적 락
    private Long version;
}

// Service에서
@Transactional
public void sendCoffee(...) {
    Member member = repository.findById(id).orElseThrow();
    member.decreasePoint(amount);  // @Version이 자동으로 충돌 감지
    repository.save(member);
}
```

---

## 격리 절차 (Quarantine)

### Step 1: @Tag("flaky") 추가

```java
// Before
@Test
@DisplayName("동시성 테스트")
void concurrencyTest() { }

// After
@Test
@Tag("flaky")  // 플래키 테스트 마킹
@DisplayName("동시성 테스트")
void concurrencyTest() { }
```

### Step 2: Import 확인

```java
import org.junit.jupiter.api.Tag;  // 필수 import
```

### Step 3: build.gradle 설정

```groovy
test {
    useJUnitPlatform {
        if (project.hasProperty('fastTest')) {
            // CI에서 플래키 테스트 제외
            excludeTags 'sentinel', 'slow', 'quarantine', 'chaos', 'nightmare', 'integration', 'flaky'
        } else {
            // Nightly에서도 제외
            excludeTags 'sentinel', 'quarantine', 'flaky'
        }
    }
}
```

---

## 조직적 Flaky Test 관리 정책

```
+-------------------------------------------------------------+
|  1. Quarantine (격리)                                        |
|     -> 3회 이상 flaky 발생 시 @Tag("flaky")로 분리            |
+-------------------------------------------------------------+
|  2. Ownership (소유권)                                       |
|     -> flaky 테스트 발생 시 담당자 지정 (Issue 생성)           |
+-------------------------------------------------------------+
|  3. SLA (서비스 수준)                                        |
|     -> 7일 내 수정 또는 삭제 결정                             |
+-------------------------------------------------------------+
|  4. Metrics (측정)                                           |
|     -> flaky rate 모니터링 (목표: < 1%)                       |
+-------------------------------------------------------------+
```

---

## 우선순위 분류

| 우선순위 | 의미 | 예시 |
|----------|------|------|
| **P0 (Critical)** | 데이터 무결성 위험 | Race Condition, 동시성 버그 |
| **P1 (High)** | 테스트 신뢰성 저하 | Redis 타이밍 이슈 |
| **P2 (Medium)** | 성능 저하 | Testcontainers 지연 |

---

## Production Evidence

> **Impact:** 47 flaky test incidents analyzed across 2025 Q4
> **Business Cost:** 2-3 hour delays in PR validation, 15% velocity reduction
> **Solution:** 6-principle framework reduced flaky rate from 12% to <0.3% (40x improvement)

---

## 관련 문서

- [testing-guide.md](../../03_Technical_Guides/testing-guide.md) Section 24: Flaky Test 근본 원인 분석
- [flaky-test-management.md](../../03_Technical_Guides/flaky-test-management.md) - Flaky Test 관리 가이드
- [unit-test.md](unit-test.md) - Unit Test Best Practices
- [concurrency-test.md](concurrency-test.md) - Concurrency Test Best Practices
