# Sample Event: character.calculated.v1

> **Event Type:** `character.calculated.v1`
> **Version:** 1
> **Domain:** Character
> **Related Issues:** #502

## Purpose

Published when a character's equipment expectation calculation is completed. This event notifies downstream systems (MongoDB sync, analytics, notifications) that new calculation results are available.

---

## Event Envelope

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "character.calculated.v1",
  "occurredAt": "2025-03-10T14:30:00.123Z",
  "version": "1",
  "producer": "expectation-calculator",
  "idempotencyKey": "char-ocid-abc123-20250310-143000",
  "payload": {
    "characterOcid": "abc123",
    "userIgn": "MapleStory",
    "characterClass": "ARCH_MAGE_FP",
    "characterLevel": 275,
    "totalExpectedCost": "150000000000",
    "maxPresetNo": 2,
    "calculatedAt": "2025-03-10T14:30:00.000Z"
  }
}
```

---

## Field Definitions

### Common Fields (Envelope)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `eventId` | String (UUID) | Yes | Unique event identifier for tracing/deduplication |
| `eventType` | String | Yes | Always `"character.calculated.v1"` |
| `occurredAt` | String (ISO-8601) | Yes | When calculation completed |
| `version` | String | Yes | Event schema version: `"1"` |
| `producer` | String | Yes | Service that produced: `"expectation-calculator"` |
| `idempotencyKey` | String | Yes | Key: `char-ocid-{ocid}-{date}-{time}` |

### Payload Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `characterOcid` | String | Yes | Nexon OCID (unique character identifier) |
| `userIgn` | String | Yes | In-game name for display purposes |
| `characterClass` | String | Yes | Character class enum (e.g., `ARCH_MAGE_FP`) |
| `characterLevel` | Integer | Yes | Character level (1-300) |
| `totalExpectedCost` | String | Yes | Total expected meso cost as string (avoid precision loss) |
| `maxPresetNo` | Integer | Yes | Maximum preset number available |
| `calculatedAt` | String (ISO-8601) | Yes | Timestamp of calculation (may differ from occurredAt) |

---

## Payload Schema (JSON Schema)

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["characterOcid", "userIgn", "characterClass", "characterLevel", "totalExpectedCost", "maxPresetNo", "calculatedAt"],
  "properties": {
    "characterOcid": {
      "type": "string",
      "description": "Nexon OCID identifier"
    },
    "userIgn": {
      "type": "string",
      "description": "In-game character name"
    },
    "characterClass": {
      "type": "string",
      "enum": ["ARCH_MAGE_FP", "ARCH_MAGE_TC", "BISHOP", "HERO", "PALADIN", "DARK_KNIGHT", "..."],
      "description": "Character class enum"
    },
    "characterLevel": {
      "type": "integer",
      "minimum": 1,
      "maximum": 300,
      "description": "Character level"
    },
    "totalExpectedCost": {
      "type": "string",
      "pattern": "^\\d+$",
      "description": "Total expected cost in meso (as string for precision)"
    },
    "maxPresetNo": {
      "type": "integer",
      "minimum": 0,
      "description": "Maximum equipment preset number"
    },
    "calculatedAt": {
      "type": "string",
      "format": "date-time",
      "description": "When the calculation was performed"
    }
  }
}
```

---

## Consumer Usage

### MongoDB Sync Worker

```kotlin
fun handle(event: IntegrationEvent<CharacterCalculatedPayload>) {
    val payload = event.payload
    characterValuationViewRepository.upsert(
        ocid = payload.characterOcid,
        totalExpectedCost = BigDecimal(payload.totalExpectedCost),
        calculatedAt = Instant.parse(payload.calculatedAt)
    )
}
```

### Analytics Consumer

```kotlin
fun handle(event: IntegrationEvent<CharacterCalculatedPayload>) {
    metrics.counter("character.calculated",
        "class" to event.payload.characterClass,
        "levelRange" to event.payload.characterLevel / 10 * 10
    ).increment()
}
```

---

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                   Expectation Calculation                    │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  User Request ──▶ Calculator Service ──▶ Event Publisher    │
│                                              │               │
│                                              ▼               │
│                              character.calculated.v1         │
│                                              │               │
│                    ┌─────────────────────────┼────────────┐ │
│                    │                         │            │ │
│                    ▼                         ▼            ▼ │
│              MongoDB Sync            Analytics       Notifications│
│              (Materialized View)     (Metrics)      (Optional)   │
└─────────────────────────────────────────────────────────────┘
```

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| v1 | 2025-03-10 | Initial definition |
