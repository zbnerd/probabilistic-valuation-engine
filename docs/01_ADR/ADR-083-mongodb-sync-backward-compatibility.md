# ADR-083: MongoDBSyncWorker Backward Compatibility for Message Format

**Status:** Proposed
**Date:** 2026-02-23
**Author:** worker-2 (fix-critical-issues team)
**Related Issues:** #4 (P1), #354 (V5 CQRS)

---

## Executive Summary

Fix message loss during V5 CQRS rollout by adding backward compatibility for legacy Redis Stream message format. During rollout, old messages use `payload` key while new messages use `data` key.

## Problem Statement

### Observed Symptoms

| Symptom | Severity | Evidence |
|---------|----------|----------|
| Messages ACKed but not synced to MongoDB | P1 | Legacy messages hit null branch |
| Data loss during gradual rollout | P1 | Old format messages are dropped |
| No deprecation warning for legacy format | P2 | Silent data loss |

### Root Cause Analysis

**Current Implementation:**
```java
// MongoDBSyncWorker.processMessage() - Line 249
String payloadJson = data.get("payload");  // Only checks "payload" key
if (payloadJson == null) {
    log.warn("[MongoDBSyncWorker] No payload in message");
    return;  // Message ACKed and lost!
}
```

**Publisher Implementation:**
```java
// MongoSyncEventPublisher.convertToStreamMap() - Line 136
map.put("payload", payloadJson);  // Writes to "payload" key
```

**The Issue:**
- The code assumes messages use `payload` key
- But older events in `character-sync` stream were written under different key structure
- Worker reads `data.get("payload")` - gets `null` for legacy messages
- Null check passes → message is ACKed → **DATA LOSS**

---

## Decision

### Solution Architecture

#### 1. Dual-Key Check with Fallback

**Implementation Pattern:**

```java
private void processMessage(StreamMessageId messageId, Map<String, String> data) {
    TaskContext context = TaskContext.of("MongoDBSyncWorker", "ProcessMessage", messageId.toString());

    executor.executeVoid(() -> {
        // Try new format first, then legacy format
        String payloadJson = extractPayloadJson(data);

        if (payloadJson == null) {
            log.warn("[MongoDBSyncWorker] No payload in message (both formats tried)");
            return;
        }

        deserializeAndSync(messageId, payloadJson);
    }, context);
}

/**
 * Extract payload JSON with backward compatibility.
 *
 * <p>Priority order:
 * <ol>
 *   <li>"data" key - New format (V5 CQRS)
 *   <li>"payload" key - Legacy format (pre-V5)
 * </ol>
 *
 * <p>Logs deprecation warning when using legacy format.
 */
private String extractPayloadJson(Map<String, String> data) {
    // Try new format first
    String payloadJson = data.get("data");
    if (payloadJson != null) {
        return payloadJson;
    }

    // Fallback to legacy format
    payloadJson = data.get("payload");
    if (payloadJson != null) {
        log.warn("[MongoDBSyncWorker] Legacy message format detected (using 'payload' key). "
                + "This format is deprecated. Please migrate to 'data' key format.");
        return payloadJson;
    }

    return null;
}
```

#### 2. Migration Path

**Phase 1: Backward Compatibility (Current)**
- Worker accepts both `data` and `payload` keys
- Logs deprecation warning for legacy format
- No data loss during rollout

**Phase 2: Monitor Legacy Usage**
- Track deprecation warning count via Prometheus counter
- When legacy message count drops below threshold (< 1% of total), proceed to Phase 3

**Phase 3: Remove Legacy Support**
- Remove `payload` key fallback
- Only accept `data` key
- Update ADR documenting legacy format removal

---

## Consequences

### Positive Effects

1. **No Data Loss:** Legacy messages are properly processed during rollout
2. **Gradual Migration:** Can deploy at own pace without breaking existing streams
3. **Observability:** Deprecation warnings track migration progress
4. **Safe Rollback:** Can revert to old publisher if needed

### Negative Effects

1. **Code Complexity:** Additional null check and fallback logic
2. **Logging Noise:** Deprecation warnings may clutter logs (mitigate with metrics)
3. **Maintenance Burden:** Need to track legacy message usage

### Mitigation Strategies

1. **Prometheus Counter:** Track legacy vs new format usage
2. **Structured Logging:** Use log level warn for deprecation (visible but not spam)
3. **Documentation:** Clearly mark legacy support as temporary in code comments

---

## Implementation Details

### Files Modified

**Modified Files (1):**
- `MongoDBSyncWorker.java` - Add `extractPayloadJson()` helper method

### Prometheus Metrics

Add new counter to track message format usage:

```java
private final Counter legacyFormatCounter;  // Injected via constructor

private String extractPayloadJson(Map<String, String> data) {
    String payloadJson = data.get("data");
    if (payloadJson != null) {
        return payloadJson;
    }

    payloadJson = data.get("payload");
    if (payloadJson != null) {
        log.warn("[MongoDBSyncWorker] Legacy message format detected");
        legacyFormatCounter.increment();  // Track usage
        return payloadJson;
    }

    return null;
}
```

**Metric:**
- Name: `mongodb_sync_legacy_format_total`
- Type: Counter
- Labels: `stream=character-sync`

### Section 12 Compliance (Zero Try-Catch)

All exception handling delegated to LogicExecutor:

```java
executor.executeVoid(
    () -> {
        String payloadJson = extractPayloadJson(data);
        if (payloadJson != null) {
            deserializeAndSync(messageId, payloadJson);
        }
    },
    context);
```

---

## Testing Strategy

### Unit Tests

**Test Cases:**

1. **New Format (`data` key)** - Normal processing
2. **Legacy Format (`payload` key)** - Fallback with deprecation warning
3. **Both Keys Present** - Prefer `data` over `payload`
4. **Neither Key Present** - Log warning and skip message

### Test Implementation

```java
@Test
@DisplayName("Legacy format: use 'payload' key with deprecation warning")
void shouldProcessLegacyFormatWithDeprecationWarning() {
    // Given: Legacy message format
    Map<String, String> data = new HashMap<>();
    data.put("payload", validEventJson);

    // When: Process message
    worker.processSingleMessage(stream, messageId, data);

    // Then: Message processed, deprecation warning logged
    verify(logAppender).logWarn(contains("Legacy message format detected"));
    verify(queryService).upsert(any(CharacterValuationView.class));
}

@Test
@DisplayName("Both keys present: prefer 'data' over 'payload'")
void shouldPreferDataKeyWhenBothPresent() {
    // Given: Both keys present (shouldn't happen, but defensive)
    Map<String, String> data = new HashMap<>();
    data.put("data", newEventJson);      // New format
    data.put("payload", oldEventJson);    // Legacy format

    // When & Then: Use 'data' key
    // ... verification
}
```

---

## Monitoring & Observability

### Prometheus Metrics

| Metric | Type | Purpose |
|--------|------|---------|
| `mongodb_sync_legacy_format_total` | Counter | Track legacy format usage |
| `mongodb_sync_processed_total` | Counter | Total messages processed |

### Loki Log Queries

**Legacy Format Detection:**
```logql
{app="maple-expectation", level="warn"}
|= "Legacy message format detected"
| count by (1m)
```

**Migration Progress:**
```logql
sum(rate(mongodb_sync_legacy_format_total[5m])) /
sum(rate(mongodb_sync_processed_total[5m]))
```

---

## References

### Related ADRs

| ADR | Topic | Link |
|-----|-------|------|
| ADR-081 | V5 CQRS Redis Stream Idempotency | [Link](ADR-081-v5-cqrs-redis-stream-idempotency-fix.md) |
| ADR-037 | V5 CQRS Command Side | [Link](ADR-037-v5-cqrs-command-side.md) |

### Code References

| File | Lines | Description |
|------|-------|-------------|
| `MongoDBSyncWorker.java` | 242-258 | Current processMessage() implementation |
| `MongoSyncEventPublisher.java` | 123-139 | Stream map creation with 'payload' key |

---

## Appendix: Implementation Checklist

- [ ] Add `extractPayloadJson()` helper method
- [ ] Add Prometheus counter for legacy format tracking
- [ ] Update `processMessage()` to use helper
- [ ] Add unit tests for all format scenarios
- [ ] Update documentation
- [ ] Monitor legacy format usage in production

---

**Document Version:** 1.0
**Status:** Proposed
**Last Updated:** 2026-02-23
**Owner:** worker-2 (fix-critical-issues team)
