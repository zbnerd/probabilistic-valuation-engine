---
id: GR-CACHE-001
category: cache
severity: critical
keywords: [ADR-003, TieredCache, SingleFlight, Cache-Stampede, Thundering-Herd]
---
# Cache Guardrails - TieredCache & SingleFlight

## Overview

Guardrails for multi-tier caching and SingleFlight pattern to prevent cache stampede and thundering herd problems.

**Source:** ADR-003: Tiered Cache & SingleFlight Pattern

---

## GR-CACHE-001: Cache Stampede Prevention

### DON'T (Anti-Patterns)

```java
// ❌ CACHE STAMPEDE: No synchronization
@Cacheable(value = "equipment", key = "#ocid")
public EquipmentData getEquipment(String ocid) {
    return nexonApiClient.fetch(ocid);
}
// Problem: When cache expires, 100 concurrent requests = 100 API calls
```

**Consequences:**
- 100 concurrent requests → 100 API calls (DB/external API overload)
- Thread pool exhaustion
- p99 latency: 2,340ms → 180ms (with SingleFlight)

### DO (Best Practices)

```java
// ✅ TIERED CACHE + SINGLE FLIGHT
public class TieredCacheManager extends AbstractCacheManager {
    private final CacheManager l1Manager;      // Caffeine (local)
    private final CacheManager l2Manager;      // Redis (distributed)
    private final SingleFlightExecutor<?> singleFlight;

    @Override
    public Cache getCache(String name) {
        return cachePool.computeIfAbsent(name, this::createTieredCache);
    }

    private Cache createTieredCache(String name) {
        return new TieredCache(
            l1Manager.getCache(name),
            l2Manager.getCache(name),
            singleFlight
        );
    }
}
```

### Cache Lookup Flow

```
[Request]
    ↓
[L1 Cache - Caffeine]  ← HIT: < 5ms
    ↓ miss
[L2 Cache - Redis]     ← HIT: < 20ms
    ↓ miss
[SingleFlight]         ← Merge concurrent requests for same key
    ↓
[External API / DB]    ← Single call even with 100 concurrent requests
```

### Performance Metrics

| Scenario | Without SingleFlight | With SingleFlight | Improvement |
|----------|---------------------|-------------------|-------------|
| 100 concurrent requests | 100 API calls | **1 API call** | **-99%** |
| p99 Latency | 2,340ms | **180ms** | **-92%** |
| DB Connection Pool | Spikes | Stable | Resolved |

---

## GR-CACHE-002: Follower Timeout Isolation

### DON'T (Anti-Patterns)

```java
// ❌ SHARED FUTURE: All followers share same promise
private CompletableFuture<T> executeAsFollower(String key, CompletableFuture<T> leaderFuture) {
    return leaderFuture;  // Problem: One follower timeout affects all
}
```

**Consequences:**
- One follower timeout → All followers affected
- Cascading failures
- Lock isolation broken

### DO (Best Practices)

```java
// ✅ ISOLATED FUTURE: Each follower has independent timeout
private CompletableFuture<T> executeAsFollower(String key, CompletableFuture<T> leaderFuture) {
    CompletableFuture<T> isolatedFuture = new CompletableFuture<>();
    leaderFuture.whenComplete((result, error) -> {
        if (error != null) isolatedFuture.completeExceptionally(error);
        else isolatedFuture.complete(result);
    });

    return isolatedFuture
            .orTimeout(followerTimeoutSeconds, TimeUnit.SECONDS)
            .exceptionallyCompose(e -> handleFollowerException(key, e));
}
```

---

## GR-CACHE-003: Cache Configuration Best Practices

### Cache Name Configuration

| Cache Name | L1 TTL | L1 Max | L2 TTL | Purpose |
|------------|--------|--------|--------|---------|
| `equipment` | 5 min | 5,000 | 10 min | Nexon API equipment data |
| `cubeTrials` | 10 min | 5,000 | 20 min | Cube probability calculation |
| `ocidCache` | 30 min | 5,000 | 60 min | OCID mapping |
| `expectationV4` | 60 min | 5,000 | 60 min | Expectation calculation results |

### DON'T (Anti-Patterns)

```java
// ❌ UNBOUNDED CACHE: Memory leak risk
@Cacheable(value = "equipment")
public EquipmentData getEquipment(String ocid) {
    // No TTL, no size limit → OOM
}
```

### DO (Best Practices)

```java
// ✅ BOUNDED CACHE WITH TTL
@Bean
public CacheManager cacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager();
    manager.setCaffeine(Caffeine.newBuilder()
        .maximumSize(5000)           // Size limit
        .expireAfterWrite(5, TimeUnit.MINUTES)  // TTL
        .recordStats());              // Metrics
    return manager;
}
```

---

## GR-CACHE-004: Graceful Degradation

### DON'T (Anti-Patterns)

```java
// ❌ REDIS FAILURE → SERVICE FAILURE
public <T> T get(String key) {
    return redisTemplate.opsForValue().get(key);  // Throws exception
}
```

### DO (Best Practices)

```java
// ✅ GRACEFUL DEGRADATION: Redis failure → Fallback to direct load
public <T> T executeWithDistributedLock(String key, Supplier<T> valueLoader) {
    // Redis failure → return false → execute without lock
    boolean acquired = executor.executeOrDefault(
        () -> lock.tryLock(30, TimeUnit.SECONDS),
        false,  // Fallback: Lock acquisition failed
        TaskContext.of("Cache", "AcquireLock", key)
    );

    if (!acquired) {
        return valueLoader.get();  // Fallback: Execute directly
    }

    try {
        return executor.execute(valueLoader::get, context);
    } finally {
        unlockSafely(key);
    }
}

// ✅ SAFE UNLOCK: Check if held by current thread
private void unlockSafely(String key) {
    executor.executeOrDefault(
        () -> {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            return null;
        },
        null,  // Ignore if unlock fails
        TaskContext.of("Cache", "Unlock", key)
    );
}
```

### Graceful Degradation Points

| Location | Failure Mode | Fallback |
|----------|-------------|----------|
| `getCachedValueFromLayers()` | L2.get() fails | null → valueLoader executes |
| `executeWithDistributedLock()` | lock.tryLock() fails | false → Direct execution |
| `executeDoubleCheckAndLoad()` | L2.get() fails | null → Direct DB query |
| `unlockSafely()` | lock.unlock() fails | null → Already auto-released |

---

## GR-CACHE-005: Redis Cluster Hash Tag

### DON'T (Anti-Patterns)

```java
// ❌ CROSS-SLOT FAILURE: Lua script without hash tag
String script = "local val = redis.call('GET', KEYS[1]) " +
                "redis.call('SET', KEYS[2], val)";  // Keys on different slots!
redisson.getScript().eval(script, RScript.Mode.READ_WRITE,
    List.of("key1", "key2"), "value");
// Error: CROSSSLOT Keys in request don't hash to the same slot
```

### DO (Best Practices)

```java
// ✅ HASH TAG: Force keys to same slot
String script = "local val = redis.call('GET', KEYS[1]) " +
                "redis.call('SET', KEYS[2], val)";
String hashTag = "{user:" + userId + "}";  // Hash tag part
redisson.getScript().eval(script, RScript.Mode.READ_WRITE,
    List.of(hashTag + ":data", hashTag + ":metadata"), "value");
// Both keys hash to same slot: {user:123}
```

**Rule:** In Redis Cluster, all keys in Lua script must have hash tag `{...}` in same position.

---

## Fail If Wrong Conditions

This ADR is invalidated if:

1. **[F1]** Cache Stampede occurs (SingleFlight not working)
   - Verification: Chaos Test N01 fails
   - Evidence: 100 requests → >1 API call

2. **[F2]** L1 cache memory leak (OOM occurs)
   - Verification: Heap dump analysis
   - Evidence: Cache unbounded growth

3. **[F3]** Follower timeout causes bottleneck (p99 > 500ms)
   - Verification: Prometheus metrics
   - Evidence: `singleflight_follower_timeout_total` increasing

4. **[F4]** Redis Cluster Cross-Slot failure
   - Verification: Application logs
   - Evidence: `CROSSSLOT Keys in request don't hash to the same slot`

---

## Verification Commands

### Cache Stampede Prevention

```bash
# N01: Thundering Herd (Cache Stampede) test
./gradlew test --tests "maple.expectation.chaos.nightmare.N01ThunderingHerdTest"

# N05: Hot Key test
./gradlew test --tests "maple.expectation.chaos.nightmare.N05HotKeyTest"

# SingleFlight Follower Timeout test
./gradlew test --tests "maple.expectation.global.concurrency.SingleFlightExecutorTest"
```

### Cache Performance

```bash
# Cache Hit Rate
rate(cache_hits_total[5m]) / (rate(cache_hits_total[5m]) + rate(cache_misses_total[5m]))

# SingleFlight Leader/Follower ratio
rate(singleflight_leader_total[5m]) / rate(singleflight_follower_total[5m])

# DB Connection Pool usage
hikaricp_connections_active / hikaricp_connections_max
```

### Redis Cluster Verification

```bash
# Verify hash tag usage in Lua scripts
grep -r "redis.call\|redis.call" src/main/java/ | grep -v "{.*}"

# Test Redis Cluster connectivity
redis-cli -c -h localhost -p 6379 cluster info
```

---

## Related Documents

- **ADR-003:** Tiered Cache & SingleFlight Pattern
- **ADR-007:** AOP, Async, Cache Integration
- **infrastructure.md** Section 8: Redis & Redisson Integration
- **infrastructure.md** Section 8-1: Redis Lua Script & Cluster Hash Tag
- **infrastructure.md** Section 17: TieredCache & Cache Stampede Prevention
