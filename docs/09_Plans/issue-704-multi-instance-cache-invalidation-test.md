# Issue #704: Multi-Instance Cache Invalidation Consistency Test

## Context

PostgreSQL LISTEN/NOTIFY 기반 분산 캐시 무효화가 여러 인스턴스 간에 정상 동작하는지 검증. 다중 인스턴스 환경에서 L1(Caffeine) 일관성이 보장되는지 테스트.

## Approach: Direct Component Construction

SpringApplicationBuilder 대신 직접 객체 생성. 이유:
- `@ConditionalOnProperty` 체인이 복잡 (cache.l2.impl=postgres + cache.invalidation.impl=postgres)
- 검증 대상은 LISTEN/NOTIFY 메커니즘이지 Spring bean wiring이 아님
- 직접 생성으로 instanceId 제어, 초기화 순서 보장 용이

## File to Create

`module-app/src/test/kotlin/maple/expectation/integration/cache/MultiInstanceCacheInvalidationTest.kt`

## Critical: cache_storage Table

`cache_storage` 테이블이 **코드베이스 어디에도 CREATE**되지 않음 (V102, V107은 인덱스만 생성). 테스트 설정에서 직접 생성 필요:

```sql
CREATE UNLOGGED TABLE IF NOT EXISTS cache_storage (
    cache_key VARCHAR(500) PRIMARY KEY,
    cache_value BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
```

`@BeforeAll`에서 `JdbcTemplate.execute()`로 실행.

## Architecture

```
Testcontainers PostgreSQL (shared singleton from TestcontainersConfiguration)
├── Instance A (instanceId="test-A", own Caffeine L1, shared PG L2)
├── Instance B (instanceId="test-B", own Caffeine L1, shared PG L2)
└── Instance C (instanceId="test-C", own Caffeine L1, shared PG L2)
```

## Initialization Order (CRITICAL)

순서가 틀리면 Supplier가 기본값("unknown", NO-OP)을 캡처:

```
1. Create shared components (DataSource, JdbcTemplate, ObjectMapper, LogicExecutor, MeterRegistry)
2. Create cache_storage table via JdbcTemplate
3. Create shared L2: PostgresL2CacheStrategy → PostgresL2CacheFactory
4. Create shared publisher: PostgresNotifyPublisher
5. Per instance:
   a. Create CaffeineCacheManager → registerCustomCache("testCache", Caffeine spec)
   b. Create TieredCacheManager(l1Manager, l2Factory, executor, null, meterRegistry, 5)
   c. Call tieredCacheManager.initializeInstanceId("test-X")  ← BEFORE getCache()
   d. Call tieredCacheManager.initializeInvalidationCallback(callback) ← BEFORE getCache()
   e. Call tieredCacheManager.getCache("testCache") ← triggers TieredCache creation with correct Suppliers
   f. Create PostgresNotifySubscriber with unique instanceId
6. Call subscriber.subscribe() on all instances ← triggers LISTEN
7. Await subscriber readiness (100ms Awaitility)
```

### Why order matters
`TieredCacheManager.getCache()` → `createTieredCache()` → creates `TieredCache` with `instanceIdSupplier` and `callbackSupplier` from `AtomicReference`. If `initializeInstanceId()` hasn't been called yet, the Supplier returns "unknown" → self-skip fails, events published with wrong sourceInstanceId.

## CacheInstance Helper

```kotlin
data class CacheInstance(
    val instanceId: String,
    val tieredCacheManager: TieredCacheManager,
    val publisher: CacheInvalidationPublisher,
    val subscriber: PostgresNotifySubscriber,
) {
    fun l1Cache(): Cache? = tieredCacheManager.getL1CacheDirect("testCache")
}
```

## Shared Components

```kotlin
// DataSource from Testcontainers
val container = TestcontainersConfiguration.postgresContainer
val dataSource: DataSource = HikariDataSource().apply {
    jdbcUrl = container.jdbcUrl
    username = container.username
    password = container.password
    maximumPoolSize = 10
}

val jdbcTemplate = JdbcTemplate(dataSource)
val objectMapper = ObjectMapper()
val meterRegistry = SimpleMeterRegistry()
val executor: LogicExecutor = LogicExecutorImpl(meterRegistry)  // check exact constructor

// Shared L2
val l2Strategy = PostgresL2CacheStrategy(jdbcTemplate, executor, objectMapper, meterRegistry)
val l2CacheFactory = PostgresL2CacheFactory(l2Strategy, executor, meterRegistry)

// Shared publisher
val publisher = PostgresNotifyPublisher(jdbcTemplate, objectMapper, executor, meterRegistry)
```

## Per-Instance Construction

```kotlin
fun createInstance(instanceId: String): CacheInstance {
    // 1. L1 (Caffeine) - unique per instance
    val l1Manager = CaffeineCacheManager()
    l1Manager.registerCustomCache("testCache",
        Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1000L)
            .recordStats()
            .build()
    )

    // 2. TieredCacheManager
    val tcm = TieredCacheManager(l1Manager, l2CacheFactory, executor, null, meterRegistry, 5)

    // 3. Initialize BEFORE creating any TieredCache
    tcm.initializeInstanceId(instanceId)
    tcm.initializeInvalidationCallback(Consumer { event -> publisher.publish(event) })

    // 4. Trigger cache creation (TieredCache captures Suppliers now)
    tcm.getCache("testCache")

    // 5. Subscriber with unique instanceId
    val subscriber = PostgresNotifySubscriber(
        dataSource, tcm, objectMapper, executor, meterRegistry,
        instanceId, 50L, 1000L  // pollIntervalMs=50ms (fast for tests)
    )

    return CacheInstance(instanceId, tcm, publisher, subscriber)
}
```

## Test Scenarios (6)

| # | Test | Description |
|---|------|-------------|
| 1 | `evict on A should invalidate L1 on B and C` | A.put → A.evict → B/C L1에서 키 사라짐 |
| 2 | `burst evict 50 keys should propagate` | A에 50키 put → 50키 evict → B/C 모두 무효화. 타임아웃 10s |
| 3 | `stale version event should be ignored` | B.put(key) → B.getKeyVersion=2 → subscriber receives event with version=0 → B의 L1 유지됨 |
| 4 | `self-evict: A evicts key, A subscriber skips own event` | A.evict → A의 L1은 evict됨(TieredCache.evict이 로컬 L1 선제거) + A subscriber는 자기 이벤트 스킵 |
| 5 | `CLEAR_ALL propagates to other instances` | A.put(3키) → A.clear() → B/C의 L1 전체 삭제 |
| 6 | `concurrent evicts from A and B reach C` | A.evict(key1) + B.evict(key2) 동시 → C에서 key1, key2 모두 무효화 |

### Self-evict test (Scenario 4) — Corrected Logic

`TieredCache.evict()`는 **항상 로컬 L1을 먼저 제거** (line 136):
```kotlin
executor.executeVoid({ l1.evict(key) }, context)  // local L1 always evicted
publishEvictEvent(key, versionCounter.get())       // then NOTIFY
```

Self-skip은 **subscriber에서 동작**: A가 자기 NOTIFY를 받아도 L1 재처리 안 함. 테스트 검증:
- A의 L1은 evict 후 비어있음 (정상 동작)
- B/C의 L1도 비어있음 (NOTIFY로 전파됨)
- 핵심: A subscriber가 자기 이벤트를 스킵했는지 확인 (metric counter 또는 에러 없음으로 간접 확인)

### Version skip test (Scenario 3) — How it works

1. B.put("key", "value") → `keyVersions["key"] = 2` (versionCounter.incrementAndGet)
2. A.evict("key") → publishes event with version=X
3. B subscriber receives: checks `event.version <= keyVersions["key"]` (2)
4. If stale (event.version ≤ 2), B skips L1 evict
5. Verify B's L1 still has the value

실제 구현: `PostgresNotifySubscriber.kt:234-244`:
```kotlin
val currentVersion = tieredCacheManager.getKeyVersion(event.cacheName, event.key ?: "")
if (currentVersion != null && event.version <= currentVersion) {
    return  // stale, skip
}
```

## Test Lifecycle

```kotlin
@BeforeAll: Create shared components + 3 instances + start subscribers
@BeforeEach: Clear all L1 caches + truncate cache_storage table
@AfterAll: Call subscriber.unsubscribe() on all + close DataSource
```

## Existing Code to Reuse

| Component | File |
|-----------|------|
| `TieredCacheManager` | `module-infra/.../cache/TieredCacheManager.kt` |
| `TieredCache` | `module-infra/.../cache/TieredCache.kt` |
| `PostgresNotifyPublisher` | `module-infra/.../cache/invalidation/impl/PostgresNotifyPublisher.kt` |
| `PostgresNotifySubscriber` | `module-infra/.../cache/invalidation/impl/PostgresNotifySubscriber.kt` |
| `PostgresL2CacheStrategy` | `module-infra/.../cache/tiered/PostgresL2CacheStrategy.kt` |
| `PostgresL2CacheFactory` | `module-infra/.../cache/tiered/PostgresL2CacheFactory.kt` |
| `CacheInvalidationEvent` | `module-infra/.../cache/invalidation/CacheInvalidationEvent.kt` |
| `LogicExecutor` | `module-infra/.../executor/LogicExecutor.kt` |
| `TestcontainersConfiguration` | `module-app/src/test/.../config/TestcontainersConfiguration.kt` |

## Verification

```bash
./gradlew compileTestKotlin --continue
./gradlew test --tests "maple.expectation.integration.cache.MultiInstanceCacheInvalidationTest"
./gradlew test  # regression check
```
