---
id: GR-TEST-004
category: testing
severity: critical
keywords: [Testcontainers, Singleton, Flaky, @Testcontainers, @Container, shared containers, data isolation]
languages: [java, kotlin]
---

# Testcontainers Singleton Pattern

## 개요

Testcontainers Singleton 패턴은 **컨테이너는 공유하지만 데이터는 격리**하는 전략으로, 테스트 실행 속도를 최적화하면서 Flaky Test를 방지합니다.

> **"컨테이너 수명은 JVM 동안 1회 (Singleton), 데이터 수명은 테스트마다 0으로 리셋 (격리)"**
>
> **Impact:** 플래키 테스트 80% 감소

---

## 용어 정의

### Singleton vs Reuse (혼동 주의)

| 용어 | 정의 | 범위 | CI 적용 |
|:---|:---|:---|:---|
| **Singleton** | 한 JVM 테스트 실행 동안 컨테이너 1번만 띄워 공유 | JVM 내 | ✅ 권장 |
| **Reuse** | 다음 실행에서도 같은 컨테이너 재사용 | 여러 JVM | ❌ CI 부적합 |

**중요:** 우리는 CI에서 **Singleton만 사용**하고 Reuse는 사용하지 않습니다.

### Reuse가 위험한 이유

- 수동 start 필요
- JUnit integration으로 stop되면 안 됨
- 환경에서 opt-in 필요
- 리소스 정리/네트워크 등 일부 기능이 완전치 않음
- **공식적으로 "CI에 부적합"**으로 명시

---

## DON'T (안티패턴)

### 1. @Testcontainers/@Container 혼용 (Critical)

```java
// Bad - 클래스 끝날 때 컨테이너가 내려감
@Testcontainers  // ❌ 클래스 끝날 때 stop() 호출
class BaseTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
}
```

**문제점:**
- 테스트 클래스 끝날 때 컨테이너가 내려감
- Spring 컨텍스트는 캐시로 남음
- 다음 클래스가 "죽은 컨테이너"로 붙어서 터짐

### 2. Reuse 모드 사용 (CI에서)

```java
// Bad - CI에서 Reuse 사용
public static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
    .withReuse(true);  // ❌ CI에서 불안정
```

### 3. 데이터 격리 없이 컨테이너만 공유

```java
// Bad - 데이터 공유로 Flaky 발생
@Test
void test1() {
    repository.save(new Entity("test"));
}

@Test
void test2() {
    // test1의 데이터가 남아있음!
    List<Entity> results = repository.findAll();
}
```

### 4. 포트만 노출하고 준비 완료 신호 없음

```java
// Bad - 컨테이너 준비 안 됐는데 연결 시도
static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
    .withExposedPorts(6379);  // ❌ waitingFor() 없음
```

---

## DO (베스트 프랙티스)

### 1. Singleton 패턴 구현

```java
// Good - static initializer로 직접 시작
public final class SharedContainers {
    public static final MySQLContainer<?> MYSQL =
        new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    public static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());  // 준비 완료 대기

    static {
        // 직접 시작 (JUnit Extension 사용 안 함)
        Startables.deepStart(Stream.of(MYSQL, REDIS)).join();
    }

    private SharedContainers() {
        throw new UnsupportedOperationException("Utility class");
    }
}
```

### 2. DynamicPropertySource로 속성 연결

```java
public abstract class InfraIntegrationTestSupport {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedContainers.MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", SharedContainers.MYSQL::getUsername);
        registry.add("spring.datasource.password", SharedContainers.MYSQL::getPassword);
        registry.add("spring.data.redis.host", SharedContainers.REDIS::getHost);
        registry.add("spring.data.redis.port",
            () -> SharedContainers.REDIS.getMappedPort(6379).toString());
    }
}
```

### 3. 데이터 격리: TRUNCATE + FLUSHDB

```java
// Good - 매 테스트마다 데이터 리셋
@BeforeEach
void resetDatabaseAndRedisState() {
    flushRedis();
    truncateAllTables();
}

private void flushRedis() {
    if (redisTemplate == null) return;
    var connection = redisTemplate.getConnectionFactory().getConnection();
    try {
        connection.flushDb();
    } finally {
        connection.close();
    }
}

private void truncateAllTables() {
    List<String> tables = TABLES.updateAndGet(prev ->
        prev != null ? prev : loadTableNames());  // 캐싱

    jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
    try {
        for (String table : tables) {
            jdbc.execute("TRUNCATE TABLE `" + table + "`");
        }
    } finally {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}

private List<String> loadTableNames() {
    return jdbc.queryForList("""
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_type = 'BASE TABLE'
          AND table_name <> 'flyway_schema_history'
        """, String.class);
}
```

### 4. 마이그레이션 타이밍: 1회만 실행

```java
// Good - 컨테이너 시작 후 1회만 migrate
static {
    Startables.deepStart(Stream.of(SharedContainers.MYSQL)).join();

    // 1회만 Flyway 실행
    try (Connection conn = DriverManager.getConnection(
        SharedContainers.MYSQL.getJdbcUrl(),
        SharedContainers.MYSQL.getUsername(),
        SharedContainers.MYSQL.getPassword()
    )) {
        Flyway.configure()
            .dataSource(conn)
            .load()
            .migrate();
    }
}
```

### 5. @DirtyStateTest 마커로 선택적 정리

```java
// Good - 대부분 롤백, 필요할 때만 TRUNCATE
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface DirtyStateTest {
    // 커밋/비동기/외부 트랜잭션이 개입될 수 있는 테스트에만 붙임
}

// 베이스 클래스
@BeforeEach
void resetState(TestInfo info) {
    boolean dirty = info.getTestMethod()
        .map(m -> m.isAnnotationPresent(DirtyStateTest.class))
        .orElse(false)
        || info.getTestClass()
        .map(c -> c.isAnnotationPresent(DirtyStateTest.class))
        .orElse(false);

    if (dirty) {
        flushRedis();
        truncateAllTables();  // 무거운 정리
    }
    // else: @DataJpaTest 기본 롤백에 의존
}
```

---

## 컨테이너 수명 vs 데이터 수명

### 핵심 원칙

> **"컨테이너를 공유해도, 데이터는 공유하면 안 됨"**

Singleton은 **속도 최적화**지, **상태 공유를 허용**하는 설계가 아닙니다.
**플래키의 80%는 데이터 공유에서 나옵니다.**

| 구분 | 수명 | 범위 | 구현 |
|------|------|------|------|
| **컨테이너** | JVM 동안 1회 | Singleton | `static final` + `Startables.deepStart()` |
| **데이터** | 테스트마다 0으로 리셋 | 격리 | `@BeforeEach` TRUNCATE/FLUSHDB |

---

## 데이터 격리 전략 비교

| 전략 | 장점 | 단점 | 추천 상황 |
|------|------|------|-----------|
| **TRUNCATE + FLUSHDB** | 롤백 새지 않음, 가장 단단함 | 테스트 많으면 비용 증가 | 기본값 (권장) |
| **@Transactional 롤백** | 엄청 빠름 | 별도 스레드/커밋 시 샘 | 단순 CRUD 테스트 |
| **@DirtyStateTest 마커** | 필요할 때만 무거운 정리 | 마커 관리 필요 | 혼합 상황 |
| **스키마 분리** | 완전 격리, 병렬 안전 | 구현 복잡 | 병렬 실행 필요 시 |

---

## 병렬 실행 주의사항

### 기본: 병렬 OFF

```groovy
// integrationTest 태스크는 기본 병렬 OFF
tasks.register('integrationTest', Test) {
    maxParallelForks = 1  // 기본값

    useJUnitPlatform {
        includeTags 'integration'
    }
}
```

### 병렬 실행 시 리소스 락

```java
@Test
@ResourceLock(value = "mysql", mode = ResourceAccessMode.READ_WRITE)
void parallelSafeTest() {
    // MySQL 리소스 락 획득
}
```

---

## WaitingFor 전략

### 포트만 노출 (부족함)

```java
// Bad - 포트만 노출
.withExposedPorts(6379)
```

### 로그 기반 대기 (권장)

```java
// Good - 로그 기반 준비 확인
.waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
```

### 헬스체크 (권장)

```java
// Good - HTTP 헬스체크
.waitingFor(Wait.forHttp("/actuator/health")
    .forPort(8080)
    .forStatusCode(200))
```

---

## 플래키 방지 체크리스트

### Singleton 패턴 검증

- [ ] `static final` 필드로 컨테이너 선언
- [ ] `static {}` initializer에서 `Startables.deepStart().join()` 호출
- [ ] `@Testcontainers`/@Container 애노테이션 사용 안 함
- [ ] `withReuse(true)` 사용 안 함 (CI에서)

### 데이터 격리 검증

- [ ] `@BeforeEach`에서 데이터 리셋
- [ ] Redis: `FLUSHDB` 또는 키 프리픽스 분리
- [ ] MySQL: `TRUNCATE` 또는 `@Transactional` 롤백
- [ ] Flyway: `flyway_schema_history`는 TRUNCATE 제외

### 병렬 실행 주의사항

- [ ] `integrationTest` 태스크는 **기본 병렬 OFF**
- [ ] 공유 DB/Redis면 병렬 실행 금지
- [ ] 부득이 병렬 시 `@ResourceLock(value="mysql", mode=READ_WRITE)`

### 준비 완료 신호

- [ ] 단순 포트 노출(exposedPorts)만 사용 금지
- [ ] `waitingFor()` WaitStrategy 명시
- [ ] healthcheck 또는 로그 기반 대기 전략

### 마이그레이션 타이밍

- [ ] 공유 컨테이너 시작 직후 **1회만 migrate**
- [ ] 동시에 migrate 시 락/경합으로 플래키 발생
- [ ] `integrationTest`는 병렬 OFF로 migrate 경합 방지

---

## Singleton 설계 한 줄 요약

> **"컨테이너 라이프사이클은 공유하되, 데이터 라이프사이클은 테스트마다 격리했다(롤백/정리/스키마 분리 + 병렬 락). 그래서 속도와 신뢰성을 동시에 확보했다."**

---

## 관련 문서

- [testing-guide.md](../../03_Technical_Guides/testing-guide.md) Section 25: 경량 테스트 강제 규칙
- [testcontainers-singleton-implementation.md](../../03_Technical_Guides/testcontainers-singleton-implementation.md) - Singleton 패턴 구현 가이드
- [testcontainers-singleton-flaky-prevention.md](../../03_Technical_Guides/testcontainers-singleton-flaky-prevention.md) - Flaky Test 방지 상세 가이드
- [flaky-test-prevention.md](flaky-test-prevention.md) - Flaky Test Prevention
- [unit-test.md](unit-test.md) - Unit Test Best Practices

---

## 참고 자료

- [Testcontainers Container Lifecycle](https://testcontainers.com/guides/testcontainers-container-lifecycle/)
- [Testcontainers JUnit 5 Integration](https://java.testcontainers.org/test_framework_integration/junit_5/)
- [JUnit 5 Parallel Execution](https://docs.junit.org/junit5/user-guide/index.html)
- [Reusable Containers (Experimental)](https://java.testcontainers.org/features/reuse/)
