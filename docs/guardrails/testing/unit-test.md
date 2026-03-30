---
id: GR-TEST-001
category: testing
severity: critical
keywords: [Thread.sleep, Awaitility, @DirtiesContext, test isolation, Testcontainers, Clock injection]
---

# Unit Test Best Practices

## 개요

이 문서는 probabilistic-valuation-engine 프로젝트에서 단위 테스트 작성 시 준수해야 할 Best Practices를 정의합니다. **Flaky Test를 방지**하고 **결정적(Deterministic)인 테스트**를 작성하는 데 초점을 둡니다.

---

## 핵심 원칙

| 원칙 | 설명 | 반대 패턴 |
|------|------|----------|
| **결정성 (Determinism)** | 같은 입력 = 같은 출력 | `LocalDate.now()`, `Math.random()` |
| **격리 (Isolation)** | 테스트 간 상태 공유 금지 | static 변수, 싱글톤 오염 |
| **독립성 (Independence)** | 실행 순서 무관성 | 테스트 순서 의존성 |
| **명시적 동기화** | `Thread.sleep()` 금지 | 고정된 대기 시간 |
| **관찰 가능성** | 실패 시 충분한 로그 |silent failures |

---

## DON'T (안티패턴)

### 1. Thread.sleep() 사용 (Critical)

```java
// Bad - 환경에 따라 다른 대기 시간
@Test
void testAsyncOperation() throws Exception {
    service.asyncMethod();
    Thread.sleep(1000);  // ❌ CI에서 실패 가능
    assertThat(result).isNotNull();
}
```

**위험성:**
- Flaky test 발생 (CI 환경에서 타이밍 차이)
- 불필요하게 긴 테스트 실행 시간
- 실제 버그를 놓칠 수 있음

### 2. 시간 기반 로직 직접 사용

```java
// Bad - 현재 시간 직접 사용
public boolean isExpired() {
    return LocalDate.now().isAfter(expiryDate);  // ❌ 자정/월말 실패
}
```

### 3. 무작위성 주입 없이 사용

```java
// Bad - 매번 다른 UUID
public String generateId() {
    return UUID.randomUUID().toString();  // ❌ 검증 어려움
}
```

### 4. @DirtiesContext 남용

```java
// Bad - 모든 테스트마다 컨텍스트 리로드
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class EveryTestNeedsFreshContext { }  // ❌ 느림
```

### 5. Testcontainers 없이 실제 외부 의존성 사용

```java
// Bad - 네트워크 불안정성
@Test
void testWithRealRedis() {
    redisTemplate.opsForValue().set("key", "value");  // ❌ CI에서 실패
}
```

---

## DO (베스트 프랙티스)

### 1. Awaitility 사용 (권장)

```java
// Good - Awaitility로 명시적 조건 대기
@Test
void testAsyncOperation() {
    service.asyncMethod();

    await()
        .atMost(5, SECONDS)
        .pollInterval(100, MILLISECONDS)
        .until(() -> result != null);

    assertThat(result).isNotNull();
}
```

### 2. Clock 주입

```java
// Good - Clock 주입으로 테스트 가능
public boolean isExpired(Clock clock) {
    return LocalDate.now(clock).isAfter(expiryDate);
}

// 테스트 코드
Clock fixedClock = Clock.fixed(Instant.parse("2024-06-15T10:00:00Z"), ZoneId.of("UTC"));
assertTrue(service.isExpired(fixedClock));
```

### 3. ID 생성기 주입

```java
// Good - Supplier로 ID 생성기 주입
public String generateId(Supplier<String> idGenerator) {
    return idGenerator.get();
}

// 테스트 코드
String fixedId = "test-id-12345";
String result = service.generateId(() -> fixedId);
assertEquals(fixedId, result);
```

### 4. @BeforeEach로 상태 격리

```java
// Good - 명시적 초기화
@BeforeEach
void setUp() {
    repository.deleteAll();
    cacheManager.getCache("myCache").clear();
    redisTemplate.delete(redisTemplate.keys("test-*"));
}
```

### 5. Testcontainers로 격리된 환경

```java
// Good - Docker로 격리된 환경
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

---

## 테스트 베이스 클래스 선택 가이드

| 테스트 유형 | 베이스 클래스 | @Tag | 이유 |
|------------|--------------|------|------|
| **단위 테스트** | 없음 (순수 JUnit) | `@Tag("unit")` | 컨테이너 불필요 |
| **일반 통합 테스트** | `IntegrationTestSupport` | `@Tag("integration")` | 기본 선택 |
| **장애 주입 테스트** | `AbstractContainerBaseTest` | `@Tag("chaos")` | Toxiproxy 필요 |
| **Sentinel HA 테스트** | `SentinelContainerBase` | `@Tag("sentinel")` | 7개 컨테이너 필요 |

```java
// 단위 테스트 예시 (베이스 클래스 없음)
class UserServiceTest {
    @Mock
    private UserRepository repository;

    @InjectMocks
    private UserService service;

    @Test
    @Tag("unit")
    void shouldCreateUser() {
        // Given
        when(repository.save(any())).thenReturn(testUser);

        // When
        User result = service.create("test");

        // Then
        assertThat(result.getName()).isEqualTo("test");
    }
}
```

---

## Gradle 태그 필터링

```bash
# PR Gate (빠른 피드백)
./gradlew test -PfastTest

# Nightly (전체 검증)
./gradlew test
```

| 태그 | 실행 시점 | 예상 시간 |
|------|----------|----------|
| `@Tag("unit")` | PR Gate | < 30초 |
| `@Tag("integration")` | PR Gate | 2-3분 |
| `@Tag("chaos")` | PR Gate | 1-2분 |
| `@Tag("sentinel")` | Nightly | 5분+ |

---

## Flaky Test 디버깅 체크리스트

1. **[ ] awaitTermination() 누락 확인** - 비동기 작업 완료 대기
2. **[ ] @BeforeEach 격리 확인** - 테스트 간 상태 초기화
3. **[ ] Testcontainers 연결 확인** - Docker 가용성 및 포트 충돌
4. **[ ] 시간 기반 로직 확인** - Clock 주입 여부
5. **[ ] static/싱글톤 상태 확인** - 전역 상태 오염
6. **[ ] CI 환경 리소스 확인** - 메모리, CPU 제한
7. **[ ] 단독 실행 vs 전체 실행** - 순서 의존성 확인

---

## Production Evidence

> **Issue:** P2 #207 - Flaky test caused 15% CI failure rate
> **Root Cause:** Missing awaitTermination() caused race conditions
> **Fix:** awaitTermination() + CountDownLatch eliminated all flakes
> **Result:** CI pass rate improved from 85% to 99.7%

---

## 관련 문서

- [testing-guide.md](../../03_Technical_Guides/testing-guide.md) - 전체 테스트 가이드
- [flaky-test-management.md](../../03_Technical_Guides/flaky-test-management.md) - Flaky Test 관리
- [flaky-test-prevention.md](flaky-test-prevention.md) - Flaky Test 방지 가드레일
- [concurrency-test.md](concurrency-test.md) - 동시성 테스트 가드레일
