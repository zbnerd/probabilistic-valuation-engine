# ADR-082: Redis Refresh Token Atomic Check-and-Mark with Lua Script

**Status:** Proposed
**Date:** 2026-02-23
**Author:** worker-2 (fix-critical-issues team)
**Related Issues:** #3 (P0), #279 (Refresh Token Implementation)

---

## Executive Summary

Fix critical security vulnerability in Refresh Token rotation where token reuse detection fails due to non-atomic check-and-mark operations. Implement atomic Lua script using Redis cjson library to prevent race condition in concurrent refresh scenarios.

## Problem Statement

### Observed Symptoms

| Symptom | Severity | Evidence |
|---------|----------|----------|
| Token reuse detection fails in concurrent scenarios | P0 | Two refresh requests can both succeed |
| TOCTOU (Time-of-Check-Time-of-Use) race condition | P0 | Non-atomic get-then-set operation |
| Previous Lua script attempt had pattern matching bugs | P1 | `string.match(tokenJson, '"used":(true|false)')` doesn't work |

### Root Cause Analysis

**Primary Issue:** Current implementation in `RedisRefreshTokenRepositoryImpl.doCheckAndMarkAsUsed()` (lines 173-206) performs:

1. `bucket.get()` - Read token JSON
2. Deserialize and check `token.used()`
3. `bucket.set()` - Mark as used

Between steps 1 and 3, another concurrent request can also read `used=false` and both requests will succeed.

**Why Previous Lua Script Failed:**

```lua
-- BUG: Lua pattern matching doesn't treat | as alternation
local usedFlag = string.match(tokenJson, '"used":(true|false)')
-- This NEVER matches 'true' because | is literal, not alternation
-- Result: usedFlag is always nil, reuse detection never triggers
```

### Security Impact

An attacker who steals a refresh token can:
1. Use the token to get a new access token
2. Before the token is marked as used, make another concurrent request
3. Both requests succeed, extending the attacker's access window

---

## Decision

### Solution Architecture

#### 1. Atomic Lua Script with cjson Library

**Key Insight:** Use Redis's built-in `cjson` library to parse JSON properly instead of pattern matching.

**Lua Script Implementation:**

```lua
-- KEYS[1]: refresh:{tokenId}
-- ARGV[1]: new TTL in milliseconds (optional, 0 for no change)

local tokenJson = redis.call('GET', KEYS[1])

-- Token doesn't exist
if tokenJson == false then
    return nil
end

-- Parse JSON using cjson library
local token = cjson.decode(tokenJson)

-- Check if already used (reuse detection)
if token.used == true then
    return 'ALREADY_USED'
end

-- Mark as used atomically
token.used = true
local newJson = cjson.encode(token)

-- Update with preserved TTL
if ARGV[1] ~= '0' then
    redis.call('PSETEX', KEYS[1], ARGV[1], newJson)
else
    redis.call('SET', KEYS[1], newJson)
end

return newJson
```

**Return Values:**
- `nil`: Token not found
- `'ALREADY_USED'`: Token reuse detected
- `newJson`: Token successfully marked as used

#### 2. Redisson Integration

**Implementation Pattern:**

```java
private Optional<RefreshToken> doCheckAndMarkAsUsed(String refreshTokenId) {
    String key = buildTokenKey(refreshTokenId);
    RBucket<String> bucket = redissonClient.getBucket(key);

    // Read first to get current TTL
    long remainingTtl = bucket.remainTimeToLive();

    // Execute atomic Lua script
    RScript script = redissonClient.getScript();
    String result = script.eval(
        RScript.Mode.READ_WRITE,
        luaScript,
        RScript.ReturnType.VALUE,
        List.of(key),
        String.valueOf(remainingTtl > 0 ? remainingTtl : 0)
    );

    // Handle return values
    if (result == null) {
        return Optional.empty(); // Token not found
    }
    if ("ALREADY_USED".equals(result)) {
        log.warn("Token reuse detected: key={}", key);
        return Optional.empty(); // Reuse detected
    }

    return Optional.of(deserializeToken(result));
}
```

#### 3. SHA-1 Digest Caching

**Optimization:** Pre-compute SHA-1 digest of Lua script for performance.

```java
private static final String LUA_SCRIPT_SHA1;

static {
    // Compute SHA-1 once at class initialization
    LUA_SCRIPT_SHA1 = digestSha1(LUA_SCRIPT);
}

private String digestSha1(String script) {
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(script.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(digest);
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-1 not available", e);
    }
}
```

---

## Consequences

### Positive Effects

1. **Atomic Operation:** Lua script guarantees indivisible read-check-write
2. **Token Reuse Detection:** Properly detects concurrent refresh attempts
3. **Performance:** SHA-1 digest caching avoids repeated script transmission
4. **Correct JSON Parsing:** cjson library handles JSON properly
5. **Test Coverage:** Concurrent tests verify atomicity

### Negative Effects

1. **Debugging Complexity:** Lua scripts are harder to debug than Java code
2. **Redis Dependency:** Script requires Redis with cjson support (available in Redis 2.6+)
3. **Script Caching:** Need to manage script digest and handle SCRIPT FLUSH scenarios

### Mitigation Strategies

1. **Comprehensive Logging:** Log all Lua script execution results
2. **Unit Tests:** Verify each return value branch (null, ALREADY_USED, success)
3. **Concurrent Tests:** Use ExecutorService to simulate race conditions
4. **Fallback Strategy:** Keep non-atomic version as fallback for Redis without cjson

---

## Implementation Details

### Files Modified

**Modified Files (2):**
- `RedisRefreshTokenRepositoryImpl.java` - Replace doCheckAndMarkAsUsed() with Lua script version
- `RefreshTokenServiceTest.java` - Add concurrent test cases

### Lua Script Design

| Component | Description |
|-----------|-------------|
| **JSON Parsing** | `cjson.decode(tokenJson)` - Proper JSON parsing |
| **Reuse Detection** | `if token.used == true then return 'ALREADY_USED' end` |
| **Atomic Update** | `token.used = true; cjson.encode(token)` - Mark and serialize |
| **TTL Preservation** | `PSETEX` with remaining TTL from original read |
| **Return Values** | `nil`, `'ALREADY_USED'`, or updated JSON |

### Section 12 Compliance (Zero Try-Catch)

All exception handling delegated to LogicExecutor:

```java
return executor.executeOrDefault(
    () -> doCheckAndMarkAsUsed(refreshTokenId),
    Optional.empty(),
    TaskContext.of("RefreshToken", "CheckAndMark", refreshTokenId)
);
```

### Section 15 Compliance (Lambda Hell Prevention)

Lua script logic extracted to private static methods:

- `digestSha1(String script)` - Compute SHA-1 digest
- `bytesToHex(byte[] bytes)` - Convert bytes to hex string
- `loadLuaScript()` - Load Lua script from classpath

---

## Testing Strategy

### Unit Tests

**Test Cases:**

1. **Token Not Found** - Returns `Optional.empty()`
2. **First Use (Success)** - Marks token as used and returns token
3. **Reuse Detection** - Returns `Optional.empty()` when `used=true`
4. **TTL Preservation** - Preserves original TTL after marking
5. **Concurrent Requests** - Two simultaneous requests, only one succeeds

### Concurrent Test Implementation

```java
@Test
@DisplayName("Concurrent refresh: only one request should succeed")
void concurrentRefresh_onlyOneSucceeds() throws Exception {
    // Given: A valid refresh token
    RefreshToken token = createTestToken();
    repository.save(token);

    // When: 10 concurrent refresh requests
    ExecutorService executor = Executors.newFixedThreadPool(10);
    List<Future<Optional<RefreshToken>>> futures = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
        futures.add(executor.submit(() ->
            repository.checkAndMarkAsUsed(token.refreshTokenId())
        ));
    }

    // Then: Only one should succeed, rest should be empty (reuse detected)
    long successCount = futures.stream()
        .map(Future::get)
        .filter(Optional::isPresent)
        .count();

    assertEquals(1, successCount, "Only one concurrent request should succeed");

    executor.shutdown();
}
```

### Integration Test

**Test Scenario:**

1. Create refresh token
2. First refresh → Success
3. Second refresh with same token → Fails (reuse detected)
4. Verify Redis state: `used=true`

---

## Monitoring & Observability

### Prometheus Metrics

| Metric | Type | Purpose |
|--------|------|---------|
| `refresh_token_reuse_detected_total` | Counter | Token reuse attempts |
| `refresh_token_atomic_check_duration_ms` | Histogram | Lua script execution time |
| `refresh_token_lua_script_errors_total` | Counter | Script execution failures |

### Loki Log Queries

**Token Reuse Detection:**
```logql
{app="maple-expectation", level="warn"}
|= "Token reuse detected"
```

**Lua Script Execution:**
```logql
{app="maple-expectation", level="debug"}
|= "Lua script executed"
|~ "duration: \\d+ms"
```

---

## References

### Related ADRs

| ADR | Topic | Link |
|-----|-------|------|
| ADR-006 | Redis Lock Lease Time & HA | [Link](ADR-006-redis-lock-lease-timeout-ha.md) |
| ADR-044 | LogicExecutor Zero Try-Catch | [Link](ADR-044-logicexecutor-zero-try-catch.md) |

### Code References

| File | Lines | Description |
|------|-------|-------------|
| `RedisRefreshTokenRepositoryImpl.java` | 160-206 | Current non-atomic implementation |
| `RedisRefreshTokenRepositoryImpl.java` | 167 | Comment about Lua script issues |

### External References

- [Redis Lua Scripting Documentation](https://redis.io/docs/manual/programmability/)
- [Redis cjson Library](https://www.redis.com.cn/commands/eval.html)
- [Redisson RScript API](https://javadoc.io/doc/org.redisson/redisson-api/3.27.0/org/redisson/api/RScript.html)

---

## Appendix: Implementation Checklist

- [ ] Write Lua script with cjson.decode()
- [ ] Implement SHA-1 digest caching
- [ ] Replace doCheckAndMarkAsUsed() with Lua script version
- [ ] Add unit tests for all return value branches
- [ ] Add concurrent test for race condition verification
- [ ] Add Prometheus metrics for monitoring
- [ ] Update documentation with Lua script logic
- [ ] Code review with team-lead

---

**Document Version:** 1.0
**Status:** Proposed
**Last Updated:** 2026-02-23
**Owner:** worker-2 (fix-critical-issues team)
