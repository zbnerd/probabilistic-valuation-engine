# Unit 4: Event Ordering & Versioning Implementation Summary

**Status**: ✅ Implementation Complete
**Priority**: P1 - High
**Issue**: Event Ordering & Versioning for Causal Consistency

## Problem Statement

Out-of-order events were corrupting the Read Model state in MongoDB. When events arrived in non-sequential order (e.g., v2 before v1), the MongoDB view could be updated with stale data, breaking causal consistency.

## Solution Overview

Implemented monotonic event versioning with buffering logic in MongoDBSyncWorker to ensure events are applied in the correct order.

## Changes Made

### 1. CharacterValuationView.kt
**File**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/CharacterValuationView.kt`

```kotlin
@Document(collection = "character_valuation_views")
data class CharacterValuationView(
    // ... existing fields ...

    /**
     * Event version for causal consistency (Unit 4: Event Ordering & Versioning)
     *
     * Ensures events are applied in monotonic order to prevent out-of-order corruption.
     */
    @Indexed var version: Long? = null,

    /**
     * Last applied event version for ordering validation
     *
     * Used by MongoDBSyncWorker to buffer out-of-order events.
     */
    var lastAppliedVersion: Long? = null,

    // ... rest of fields ...
)
```

### 2. CharacterViewQueryService.kt
**File**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/CharacterViewQueryService.kt`

**New Method**:
```kotlin
/**
 * Get the last applied event version for a user (Unit 4: Event Ordering & Versioning)
 *
 * @param userIgn User in-game name
 * @return Last applied version, or 0L if no document exists
 */
fun getLastAppliedVersion(userIgn: String): Long {
    return executor.executeOrDefault(
        {
            val view = repository.findByUserIgn(userIgn)
            view?.lastAppliedVersion ?: 0L
        },
        0L,
        TaskContext.of("MongoQuery", "GetLastAppliedVersion", userIgn)
    )
}
```

**Updated Methods**:
- `handleUpdateWithOptimisticLock()`: Now sets `lastAppliedVersion` to the incoming event version
- `handleInsertNew()`: Now sets `lastAppliedVersion` to the event version

### 3. ViewTransformer.java
**File**: `module-app/src/main/java/maple/expectation/application/service/expectation/event/ViewTransformer.java`

```java
// Unit 4 & Unit 5: Use event version for causal consistency and optimistic locking
// Event version ensures monotonic ordering and prevents out-of-order corruption
long eventVersion = event.getVersion() != null ? event.getVersion() : System.currentTimeMillis();

return new CharacterValuationView(
    deterministicId,
    event.getUserIgn(),
    event.getMessageId(),
    event.getCharacterOcid(),
    event.getCharacterClass(),
    event.getCharacterLevel(),
    parseInstant(event.getCalculatedAt()),
    Instant.now(),
    eventVersion, // Use event version for causal consistency
    parseCostToLong(event.getTotalExpectedCost()),
    event.getMaxPresetNo(),
    presetViews,
    false);
```

### 4. MongoDBSyncWorker.java
**File**: `module-app/src/main/java/maple/expectation/application/worker/MongoDBSyncWorker.java`

**New Fields**:
```java
// Unit 4: Event buffering for out-of-order events
// Key: userIgn, Value: Map of version -> BufferedEvent
private final Map<String, Map<Long, BufferedEvent>> eventBuffer = new ConcurrentHashMap<>();

private final Counter skippedCounter;   // Events skipped (already applied)
private final Counter bufferedCounter;  // Events buffered (out-of-order)
private final Counter appliedCounter;   // Events applied from buffer
```

**Core Logic**:
```java
private void deserializeAndSync(StreamMessageId messageId, String payloadJson) {
    checkedExecutor.executeUncheckedVoid(() -> {
        ExpectationCalculationCompletedEvent event = // ... deserialize ...

        String userIgn = event.getUserIgn();
        Long eventVersion = event.getVersion();
        if (eventVersion == null) {
            eventVersion = System.currentTimeMillis(); // Fallback
        }

        // Get last applied version from MongoDB
        long lastAppliedVersion = queryService.getLastAppliedVersion(userIgn);

        // Version check logic
        if (eventVersion <= lastAppliedVersion) {
            // Already applied - skip
            skippedCounter.increment();
            return;
        } else if (eventVersion > lastAppliedVersion + 1) {
            // Out-of-order - buffer it
            bufferEvent(event, messageId, payloadJson);
            bufferedCounter.increment();
            return;
        }

        // Version is exactly lastAppliedVersion + 1 - apply immediately
        applyEvent(event, messageId);

        // Try to apply any buffered events that are now in sequence
        processBufferedEvents(userIgn, eventVersion);
    }, context);
}
```

**Helper Methods**:
- `applyEvent()`: Applies event to MongoDB
- `bufferEvent()`: Stores out-of-order event in buffer
- `processBufferedEvents()`: Applies buffered events when sequence gap is filled
- `BufferedEvent`: Record holding buffered event data

## Event Ordering Logic

```
┌─────────────────────────────────────────────────────────────────┐
│                    Event Ordering Logic                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  MongoDB: lastAppliedVersion = 5                                │
│                                                                  │
│  Event v5 arrives:                                              │
│    → v5 <= lastAppliedVersion (5)                               │
│    → SKIP (already applied)                                     │
│                                                                  │
│  Event v7 arrives:                                              │
│    → v7 > lastAppliedVersion + 1 (6)                            │
│    → BUFFER (out-of-order, waiting for v6)                      │
│                                                                  │
│  Event v6 arrives:                                              │
│    → v6 == lastAppliedVersion + 1 (6)                           │
│    → APPLY immediately                                          │
│    → Check buffer: v7 is there                                  │
│    → APPLY v7 from buffer                                       │
│    → lastAppliedVersion = 7                                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Metrics

Added Micrometer counters for monitoring:
- `mongodb.sync.skipped`: Events skipped (already applied)
- `mongodb.sync.buffered`: Events buffered (out-of-order)
- `mongodb.sync.buffer.applied`: Events applied from buffer

## E2E Test Scenario

```bash
# 1. Start services
docker-compose up -d mongodb redis
./gradlew bootRun

# 2. Publish events out of order (v2 before v1)
redis-cli XADD character-sync \* data '{"version":2,"userIgn":"test"}'
redis-cli XADD character-sync \* data '{"version":1,"userIgn":"test"}'

# 3. Expected behavior:
# - v2 arrives: Buffered (waiting for v1)
# - v1 arrives: Applied immediately, then v2 is applied from buffer

# 4. Verify MongoDB state
mongo maple_expectation_v5 --eval "db.character_valuation_views.findOne({userIgn:'test'})"

# 5. Expected result:
# - Both events applied in correct order (v1 then v2)
# - lastAppliedVersion = 2
```

## Compliance

- **Section 12 (Zero Try-Catch)**: All exception handling delegated to LogicExecutor
- **Section 15 (Lambda Hell Prevention)**: Complex logic extracted to private methods
- **Thread Safety**: ConcurrentHashMap for event buffer
- **Metrics**: Comprehensive monitoring with Micrometer counters

## Trade-offs

**Memory vs Consistency**: Event buffer uses in-memory storage
- **Pro**: Fast access, simple implementation
- **Con**: Buffer lost on worker crash (events will be retried from Redis Stream PEL)

**Alternative Considered**: Redis-based buffer
- Would provide durability across crashes
- More complex implementation with additional latency
- Current approach is sufficient given Redis Stream PEL recovery

## Next Steps

1. ✅ Implementation complete
2. ⏳ Unit tests (pending build fix)
3. ⏳ E2E testing with Redis Streams
4. ⏳ Performance testing under load
5. ⏳ Monitoring dashboard setup

## References

- ADR-V5-cqrs-mongodb-readside.md
- CLAUDE.md Section 12 (Zero Try-Catch)
- CLAUDE.md Section 15 (Lambda Hell Prevention)
