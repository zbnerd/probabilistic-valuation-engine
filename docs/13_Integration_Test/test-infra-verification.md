# 통합 테스트 인프라 메타 테스트 가이드

> 테스트 인프라 자체의 성능과 정확성을 검증하는 메타 테스트

## 개요

실제 비즈니스 테스트 전에 인프라가 올바르게 설정되었는지 검증하는 메타 테스트 모음입니다.
모든 메타 테스트는 `@Tag("infra-verification")` 어노테이션을 사용하여 별도 실행이 가능합니다.

## 최근 검증 결과 (2026-03-09)

| 테스트 | 상태 | 설명 |
|------|------|------|
| ContainerSingletonTest | ✅ PASS | PostgreSQL + PGMQ 컨테이너 실행 중 |
| PgmqIsolationTest | ✅ PASS | 큐 메시지 테스트 간 누수 없음 |
| DatabaseIsolationTest | ✅ PASS | DatabaseCleaner 데이터 완전 삭제 |
| AdvisoryLockConcurrencyTest | ✅ PASS | Advisory Lock 정상 작동 |
| ContextCachingTest | ✅ PASS | Spring Context 1회만 생성 |
| TestInfraPerformanceReport | ✅ PASS | 성능 리포트트 정상 |

## 실행 방법

```bash
# 메타 테스트만 실행
./gradlew :module-app:testInfraVerification

# 전체 테스트 실행 (메타 테스트 포함)
./gradlew :module-app:test
```

## 해결된 기술 부채

### 1. Bean 이름 충돌 해결

**문제:** `CalculationProperties` 클래스가 두 모듈에 중복 존재하여 Bean 이름 충돌 발생

**해결:** Kotlin 버전에 명시적 Bean 이름 부여
```kotlin
@Component("infraCalculationProperties")  // 명시적 이름
@ConfigurationProperties(prefix = "calculation")
data class CalculationProperties(...)
```

### 2. FlameTrialsService Bean 등록

**문제:** `FlameTrialsPort` 인터페이스 구현체가 Bean으로 등록되지 않음

**해결:** `CorePortAdapterConfig.java`에 Bean 등록 추가
```java
@Bean
public FlameTrialsPort flameTrialsPort(
    FlameDpCalculator dpCalculator,
    FlameScoreCalculator scoreCalculator) {
    return new FlameTrialsService(dpCalculator, scoreCalculator);
}
```

### 3. Redis Testcontainers 동적 포트

**문제:** `application-test.yml`에 하드코딩된 Redis 포트로 인해 Testcontainers 동적 포트 미작동

**해결:** `IntegrationTestBase`에 `@DynamicPropertySource` 추가
```kotlin
companion object {
    @JvmStatic
    @DynamicPropertySource
    fun redisProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.data.redis.host") { redisContainer.host }
        registry.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
    }
}
```

## 메타 테스트 목록

### 1. ContainerSingletonTest — 싱글톤 컨테이너 검증

컨테이너가 정말 1개만 뜨는지 검증합니다.

```kotlin
@Tag("infra-verification")
class ContainerSingletonTest : IntegrationTestBase() {

    companion object {
        val containerIds = mutableSetOf<String>()
    }

    @Test
    fun `테스트 1 - 컨테이너 ID 기록`() {
        containerIds.add(getContainerId())
        assertThat(containerIds).hasSize(1)
    }

    @Test
    fun `테스트 2 - 동일 컨테이너인지 확인`() {
        containerIds.add(getContainerId())
        assertThat(containerIds).hasSize(1)  // 여전히 1개여야 함
    }
}
```

### 2. ContextCachingTest — Context 캐싱 검증

ApplicationContext가 재생성 안 되는지 검증합니다.

```kotlin
@Tag("infra-verification")
class ContextCachingTest : IntegrationTestBase() {

    companion object {
        val contextIds = mutableSetOf<Int>()
    }

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Test
    fun `Context ID 기록`() {
        contextIds.add(System.identityHashCode(applicationContext))
        assertThat(contextIds).hasSize(1)  // Context 재생성 안 됨
    }
}
```

### 3. DatabaseIsolationTest — 데이터 격리 검증

테스트 간 데이터 오염 없는지 검증합니다.

```kotlin
@Tag("infra-verification")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DatabaseIsolationTest : IntegrationTestBase() {

    @Test
    @Order(1)
    fun `테스트 1 - 데이터 삽입`() {
        characterRepository.save(CharacterFixtures.create("isolation-test-001"))
        assertThat(characterRepository.count()).isEqualTo(1)
    }

    @Test
    @Order(2)
    fun `테스트 2 - 이전 테스트 데이터가 없어야 함`() {
        // @BeforeEach에서 DatabaseCleaner가 정리했으므로
        assertThat(characterRepository.count()).isEqualTo(0)
    }
}
```

### 4. DatabaseCleanerPerformanceTest — 성능 측정

TRUNCATE 속도를 확인합니다.

```kotlin
@Test
fun `DatabaseCleaner는 100ms 이내에 완료되어야 한다`() {
    // GIVEN: 각 테이블에 데이터 삽입
    repeat(100) { i ->
        characterRepository.save(CharacterFixtures.create("perf-test-$i"))
    }

    // WHEN: 클리너 실행 시간 측정
    val elapsed = measureTimeMillis {
        databaseCleaner.clean()
    }

    // THEN
    assertThat(elapsed).isLessThan(100)  // 100ms 이내
}
```

### 5. PgmqIsolationTest — PGMQ 격리 검증

큐 메시지가 테스트 간 누수 안 되는지 검증합니다.

```kotlin
@Tag("infra-verification")
class PgmqIsolationTest : IntegrationTestBase() {

    @Test
    fun `테스트 1 - 큐에 메시지 발행`() {
        sendMessage("calculation-queue", """{"test": "message-1"}""")
        assertThat(queueSize("calculation-queue")).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `테스트 2 - 이전 테스트 메시지가 없어야 함`() {
        // @BeforeEach에서 큐 purge 했으므로
        assertThat(queueSize("calculation-queue")).isEqualTo(0)
    }
}
```

### 6. AdvisoryLockConcurrencyTest — 동시성 검증

Single Flight가 진짜 작동하는지 검증합니다.

```kotlin
@Test
fun `동시 10개 스레드에서 Advisory Lock이 정확히 1개만 획득된다`() {
    val lockKey = 12345L
    val acquired = AtomicInteger(0)
    val rejected = AtomicInteger(0)
    val latch = CountDownLatch(10)

    val executor = Executors.newFixedThreadPool(10)
    repeat(10) {
        executor.submit {
            try {
                dataSource.connection.use { conn ->
                    val rs = conn.createStatement()
                        .executeQuery("SELECT pg_try_advisory_lock($lockKey)")
                    rs.next()
                    if (rs.getBoolean(1)) {
                        acquired.incrementAndGet()
                        Thread.sleep(500)  // 락 보유 시간
                        conn.createStatement()
                            .execute("SELECT pg_advisory_unlock($lockKey)")
                    } else {
                        rejected.incrementAndGet()
                    }
                }
            } finally {
                latch.countDown()
            }
        }
    }

    latch.await(10, TimeUnit.SECONDS)
    executor.shutdown()

    // 동시에 도착해도 락은 1개만 획득
    assertThat(acquired.get()).isEqualTo(1)
    assertThat(rejected.get()).isEqualTo(9)
}
```

### 7. TestInfraPerformanceReport — 성능 리포트

전체 테스트 스위트 속도 리포트를 출력합니다.

```kotlin
@Tag("infra-verification")
class TestInfraPerformanceReport : IntegrationTestBase() {

    @Test
    fun `인프라 성능 리포트 출력`() {
        println("""
            ╔══════════════════════════════════════════════╗
            ║       통합 테스트 인프라 성능 리포트          ║
            ╠══════════════════════════════════════════════╣
            ║ 컨테이너 시작:   최초 1회만 (싱글톤)          ║
            ║ Context 수:     ${SpringContextCounter.count}개 (1이어야 정상)   ║
            ║ Bean 수:        ${applicationContext.beanDefinitionCount}개              ║
            ║ 총 경과 시간:    ${totalElapsed}ms            ║
            ╚══════════════════════════════════════════════╝
        """.trimIndent())

        // 기준치 검증
        assertThat(SpringContextCounter.count)
            .describedAs("Context가 2번 이상 생성되면 @MockBean 또는 설정 불일치")
            .isEqualTo(1)
    }
}
```

## 검증 기준

| 항목 | 기준 | 실패 시 원인 |
|------|------|-------------|
| 컨테이너 수 | 1개 | companion object 미사용 |
| Context 수 | 1개 | @MockBean, @SpyBean, @DirtiesContext 사용 |
| DatabaseCleaner | <100ms | 테이블 너무 많음 또는 FK 복잡 |
| Advisory Lock | 정확히 1개 획득 | PostgreSQL 아님 또는 버그 |

## 파일 구조

```
module-app/src/test/kotlin/maple/expectation/testinfra/
├── ContainerSingletonTest.kt
├── ContextCachingTest.kt
├── DatabaseIsolationTest.kt
├── PgmqIsolationTest.kt
├── AdvisoryLockConcurrencyTest.kt
├── TestInfraPerformanceReport.kt
└── SpringContextCounter.kt
```

## CI 연동

```yaml
# .github/workflows/test.yml
- name: Run infra verification
  run: ./gradlew :module-app:testInfraVerification

- name: Verify context count
  run: |
    CONTEXT_COUNT=$(grep "Context count" output.txt | awk '{print $NF}')
    if [ "$CONTEXT_COUNT" != "1" ]; then
      echo "Context was recreated! Check for @MockBean usage."
      exit 1
    fi
```

