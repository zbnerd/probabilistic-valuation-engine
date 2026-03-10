# Sample Event: cache.invalidated.v1

> **Event Type:** `cache.invalidated.v1`
> **Version:** 1
> **Domain:** Cache
> **Related Issues:** #502

## Purpose

Published when a cache entry is invalidated (explicitly removed or expired). This event enables cache invalidation propagation across multiple application instances in a distributed environment, ensuring cache consistency.

---

## Event Envelope

```json
{
  "eventId": "770e8400-e29b-41d4-a716-446655440002",
  "eventType": "cache.invalidated.v1",
  "occurredAt": "2025-03-10T16:00:00.789Z",
  "version": "1",
  "producer": "cache-manager",
  "idempotencyKey": "cache-user-preferences-u67890",
  "payload": {
    "cacheName": "user-preferences",
    "key": "u67890",
    "invalidationType": "EXPLICIT",
    "sourceInstanceId": "app-instance-01",
    "reason": "USER_UPDATE",
    "invalidatedAt": "2025-03-10T16:00:00.000Z"
  }
}
```

---

## Field Definitions

### Common Fields (Envelope)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `eventId` | String (UUID) | Yes | Unique event identifier for tracing/deduplication |
| `eventType` | String | Yes | Always `"cache.invalidated.v1"` |
| `occurredAt` | String (ISO-8601) | Yes | When invalidation occurred |
| `version` | String | Yes | Event schema version: `"1"` |
| `producer` | String | Yes | Service that produced: `"cache-manager"` |
| `idempotencyKey` | String | Yes | Key: `cache-{cacheName}-{key}` |

### Payload Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `cacheName` | String | Yes | Name of the cache (e.g., `user-preferences`, `character-data`) |
| `key` | String | Yes | Cache key that was invalidated |
| `invalidationType` | String | Yes | Type: `EXPLICIT`, `EXPIRED`, `EVICTED`, `CLEARED` |
| `sourceInstanceId` | String | Yes | Instance that triggered the invalidation |
| `reason` | String | No | Reason code for invalidation (e.g., `USER_UPDATE`, `DATA_REFRESH`) |
| `invalidatedAt` | String (ISO-8601) | Yes | When the invalidation happened |

---

## Payload Schema (JSON Schema)

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["cacheName", "key", "invalidationType", "sourceInstanceId", "invalidatedAt"],
  "properties": {
    "cacheName": {
      "type": "string",
      "description": "Cache name/region"
    },
    "key": {
      "type": "string",
      "description": "Cache key that was invalidated"
    },
    "invalidationType": {
      "type": "string",
      "enum": ["EXPLICIT", "EXPIRED", "EVICTED", "CLEARED"],
      "description": "How the invalidation occurred"
    },
    "sourceInstanceId": {
      "type": "string",
      "description": "Application instance that triggered invalidation"
    },
    "reason": {
      "type": "string",
      "description": "Optional reason code for invalidation"
    },
    "invalidatedAt": {
      "type": "string",
      "format": "date-time",
      "description": "When the invalidation occurred"
    }
  }
}
```

---

## Invalidation Types

| Type | Description | Example Trigger |
|------|-------------|-----------------|
| `EXPLICIT` | Intentional removal via API call | User updates profile, cache cleared |
| `EXPIRED` | TTL-based expiration | Cache entry TTL reached |
| `EVICTED` | Memory pressure eviction | Cache size limit reached |
| `CLEARED` | Full cache clear | Application deployment, admin action |

---

## Consumer Usage

### L1 Cache Invalidation (Caffeine)

```kotlin
fun handle(event: IntegrationEvent<CacheInvalidatedPayload>) {
    val payload = event.payload

    // Skip if this instance triggered the invalidation (already handled locally)
    if (payload.sourceInstanceId == instanceId) {
        return
    }

    // Invalidate local L1 cache
    l1CacheManager.invalidate(payload.cacheName, payload.key)

    log.info("Invalidated L1 cache: {} - {}", payload.cacheName, payload.key)
}
```

### Metrics Collection

```kotlin
fun handle(event: IntegrationEvent<CacheInvalidatedPayload>) {
    val payload = event.payload

    metrics.counter("cache.invalidation",
        "cacheName" to payload.cacheName,
        "type" to payload.invalidationType
    ).increment()
}
```

---

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│               Distributed Cache Invalidation                 │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────┐                                         │
│  │  Instance A     │                                         │
│  │  (Source)       │───┐                                     │
│  │  L1: key=value  │   │                                     │
│  └─────────────────┘   │                                     │
│                        ▼                                      │
│              Event: cache.invalidated.v1                      │
│                        │                                      │
│         ┌──────────────┼──────────────┐                      │
│         │              │              │                       │
│         ▼              ▼              ▼                       │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐            │
│  │ Instance B  │ │ Instance C  │ │ Instance D  │            │
│  │ L1: REMOVE  │ │ L1: REMOVE  │ │ L1: REMOVE  │            │
│  │ key         │ │ key         │ │ key         │            │
│  └─────────────┘ └─────────────┘ └─────────────┘            │
│                                                              │
│  All instances now have consistent L1 cache state            │
└─────────────────────────────────────────────────────────────┘
```

---

## Integration with TieredCache

This event is part of the TieredCache invalidation strategy:

```
┌──────────────────────────────────────────────────────────────┐
│                      TieredCache Flow                         │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  1. Read: L1 (Caffeine) → L2 (PostgreSQL UNLOGGED) → Loader  │
│                                                               │
│  2. Write: L1 → L2 → PostgreSQL NOTIFY                       │
│             │                                                 │
│             ▼                                                 │
│  3. Invalidate: Publish cache.invalidated.v1                  │
│             │                                                 │
│             ▼                                                 │
│  4. Propagate: Other instances receive event, clear L1        │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

---

## Notes

### Source Instance Filtering

Consumers MUST skip processing if `sourceInstanceId` matches their own instance ID. The source instance has already handled the invalidation locally, and processing it again would be redundant.

### Idempotency

The `idempotencyKey` format (`cache-{cacheName}-{key}`) ensures that multiple invalidation events for the same cache entry can be deduplicated if needed.

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| v1 | 2025-03-10 | Initial definition |
