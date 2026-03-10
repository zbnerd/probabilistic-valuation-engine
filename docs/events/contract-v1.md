# Event Contract v1

> **Version:** 1.0.0
> **Status:** Active
> **Related Issues:** #501, #502, #503
> **Last Updated:** 2025-03-10

## Purpose

Define a standardized contract for all integration events in the MapleExpectation system. This ensures:

- **Consistency:** All events follow the same structure and naming conventions
- **Interoperability:** Events can be processed by any consumer without custom parsing logic
- **Evolvability:** Changes can be made safely with clear backward compatibility rules
- **Traceability:** Events can be traced across distributed systems

---

## Event Naming Convention

### Format

```
{domain}.{action}.v{version}
```

### Components

| Component | Description | Examples |
|-----------|-------------|----------|
| `domain` | The bounded context or aggregate | `character`, `donation`, `cache`, `user` |
| `action` | Past-tense verb describing what happened | `calculated`, `created`, `invalidated`, `updated` |
| `version` | Semantic version (major only for events) | `v1`, `v2` |

### Naming Rules

1. **Use lowercase with dots:** `character.calculated.v1` (not `CharacterCalculatedV1`)
2. **Past tense for actions:** `created` not `create`, `calculated` not `calculate`
3. **Single version number:** Events use `v1`, `v2` (not `v1.0.0` or `v1.2`)
4. **Domain-first grouping:** Events are grouped by domain for subscription patterns

### Examples

```
character.calculated.v1      # Character expectation calculation completed
character.updated.v1         # Character data updated
donation.created.v1          # New donation record created
cache.invalidated.v1         # Cache entry invalidated
user.logged_in.v1            # User login event
```

---

## Common Fields

All events MUST include the following fields in the envelope:

### Required Fields

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `eventId` | String (UUID) | Unique identifier for this event instance. Used for deduplication and tracing. | `"550e8400-e29b-41d4-a716-446655440000"` |
| `eventType` | String | Event type identifier following naming convention | `"character.calculated.v1"` |
| `occurredAt` | String (ISO-8601) | When the event occurred in the domain (not when it was published) | `"2025-03-10T14:30:00.123Z"` |
| `version` | String | Event schema version (matches eventType version) | `"1"` |
| `producer` | String | Service/module that produced the event | `"expectation-calculator"` |
| `idempotencyKey` | String | Key for idempotent processing (often derived from aggregate ID + action) | `"char-abc123-calc-20250310"` |

### Field Constraints

- `eventId`: MUST be a valid UUID v4, globally unique
- `occurredAt`: MUST be in UTC, ISO-8601 format with milliseconds
- `version`: MUST be a string (not number) to allow "1", "2", etc.
- `producer`: SHOULD be the module/service name, not hostname or instance ID

---

## Event Envelope Structure

### JSON Schema

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "character.calculated.v1",
  "occurredAt": "2025-03-10T14:30:00.123Z",
  "version": "1",
  "producer": "expectation-calculator",
  "idempotencyKey": "char-abc123-calc-20250310",
  "payload": {
    // Domain-specific fields
  }
}
```

### Kotlin Implementation

```kotlin
data class IntegrationEvent<T>(
    val eventId: String,           // UUID
    val eventType: String,         // "character.calculated.v1"
    val occurredAt: String,        // ISO-8601 timestamp
    val version: String,           // "1"
    val producer: String,          // "expectation-calculator"
    val idempotencyKey: String,    // Deduplication key
    val payload: T                 // Domain-specific payload
)
```

---

## Payload Guidelines

### Principles

1. **Minimal payload:** Include only data needed by consumers
2. **Denormalization OK:** Include computed values if commonly needed
3. **No sensitive data:** Never include passwords, tokens, or PII
4. **Stable identifiers:** Use IDs that don't change (not names, emails)

### Payload Versioning

When payload structure changes:

| Change Type | Action | Example |
|-------------|--------|---------|
| Add optional field | Create new minor version | `v1` → `v1.1` (internal) |
| Remove field | Create new major version | `v1` → `v2` |
| Change field type | Create new major version | `v1` → `v2` |
| Add required field | Create new major version | `v1` → `v2` |

---

## Producer Responsibilities

1. **Generate unique eventId:** Use UUID v4
2. **Set accurate occurredAt:** When the event happened, not when publishing
3. **Include idempotencyKey:** Enable consumers to deduplicate safely
4. **Version correctly:** Increment version only for breaking changes
5. **Order within aggregate:** Events for same aggregate should be ordered

## Consumer Responsibilities

1. **Idempotent processing:** Use eventId or idempotencyKey to deduplicate
2. **Handle version negotiation:** Support current and N-1 versions
3. **Fail gracefully:** Unknown event types should not crash consumers
4. **Acknowledge after processing:** Only ack after successful handling

---

## Event Flow

```
┌─────────────┐    ┌──────────────┐    ┌─────────────┐
│   Domain    │───▶│   Event      │───▶│  Message    │
│   Service   │    │   Publisher  │    │  Broker     │
└─────────────┘    └──────────────┘    └─────────────┘
                          │                   │
                    Creates event         Routes to
                    with contract         consumers
                          │                   │
                          ▼                   ▼
                   ┌─────────────────────────────┐
                   │     IntegrationEvent<T>     │
                   │  - eventId (UUID)           │
                   │  - eventType (domain.action)│
                   │  - occurredAt (ISO-8601)    │
                   │  - version ("1")            │
                   │  - producer                 │
                   │  - idempotencyKey           │
                   │  - payload (domain data)    │
                   └─────────────────────────────┘
```

---

## References

- [Event Compatibility Rules](./compatibility.md) - Backward compatibility guidelines
- [Sample Events](./samples/) - Example event definitions
- [IntegrationEvent.kt](../../module-core/src/main/kotlin/maple/expectation/core/domain/event/IntegrationEvent.kt) - Core implementation
- [ADR-018](../adr/ADR-018-strategy-pattern-for-acl.md) - Anti-Corruption Layer strategy
