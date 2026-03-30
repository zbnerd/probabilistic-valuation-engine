# Event Backward Compatibility Rules

> **Version:** 1.0.0
> **Status:** Active
> **Related Issues:** #503
> **Last Updated:** 2025-03-10

## Purpose

Define rules for making changes to event schemas while maintaining backward compatibility. This ensures existing consumers continue to work when event structures evolve.

---

## Core Principles

1. **Consumers drive compatibility:** Events are produced once but consumed by many services
2. **Explicit breaking changes:** Major version bumps signal incompatible changes
3. **Graceful degradation:** Consumers should handle unknown fields gracefully
4. **Documentation required:** All schema changes must be documented

---

## Compatibility Matrix

| Change Type | Compatibility | Action Required | Example |
|-------------|---------------|-----------------|---------|
| Add optional field | ✅ ALLOWED | None | Add `nickname?: string` |
| Add required field | ❌ BREAKING | New major version | Add `userId: string` (required) |
| Remove field | ❌ BREAKING | New major version | Remove `oldField` |
| Rename field | ❌ BREAKING | New major version | `userName` → `username` |
| Change field type | ❌ BREAKING | New major version | `count: number` → `count: string` |
| Required → Optional | ✅ ALLOWED | None | `name: string` → `name?: string` |
| Optional → Required | ❌ BREAKING | New major version | `name?: string` → `name: string` |
| Add enum value | ⚠️ CAUTION | Consumer update | Add `FAILED` to status enum |
| Remove enum value | ❌ BREAKING | New major version | Remove `PENDING` from status |
| Change field format | ⚠️ CAUTION | Consumer update | ISO date → epoch millis |

### Legend

- ✅ **ALLOWED:** No version change needed, safe to deploy
- ⚠️ **CAUTION:** May work but requires consumer coordination
- ❌ **BREAKING:** Requires new major version (`v1` → `v2`)

---

## Detailed Rules

### 1. Adding Fields

#### Optional Fields (ALLOWED)

```json
// v1
{
  "userId": "u123",
  "name": "John"
}

// v1 (compatible addition)
{
  "userId": "u123",
  "name": "John",
  "nickname": "Johnny"  // NEW optional field
}
```

**Consumer behavior:** Old consumers ignore the new field. New consumers can use it.

#### Required Fields (BREAKING)

```json
// v1
{
  "userId": "u123"
}

// v2 (BREAKING - new required field)
{
  "userId": "u123",
  "email": "john@example.com"  // NEW required field
}
```

**Why breaking:** Old consumers expect events without `email`. New producers always include it.

**Solution:** Add as optional first, then deprecate old version after migration.

---

### 2. Removing Fields

```json
// v1
{
  "userId": "u123",
  "legacyField": "old data"
}

// v2 (BREAKING - field removed)
{
  "userId": "u123"
}
```

**Why breaking:** Consumers may depend on the removed field.

**Migration path:**
1. Add new field alongside old field
2. Update consumers to use new field
3. Wait for consumer migration (monitor usage)
4. Remove old field with new major version

---

### 3. Changing Field Types

```json
// v1
{
  "count": 42  // number
}

// v2 (BREAKING - type changed)
{
  "count": "42"  // string
}
```

**Why breaking:** Type expectations in consumers will fail.

**Solution:** Add new field with new type, deprecate old field.

---

### 4. Enum Changes

#### Adding Values (CAUTION)

```json
// v1
{
  "status": "ACTIVE" | "INACTIVE"
}

// v1 (cautious addition)
{
  "status": "ACTIVE" | "INACTIVE" | "PENDING"  // NEW value
}
```

**Risk:** Consumers using `switch`/`if-else` may hit default case unexpectedly.

**Mitigation:** Ensure consumers handle unknown enum values gracefully.

#### Removing Values (BREAKING)

```json
// v1
{
  "status": "ACTIVE" | "PENDING" | "INACTIVE"
}

// v2 (BREAKING - enum value removed)
{
  "status": "ACTIVE" | "INACTIVE"  // PENDING removed
}
```

**Why breaking:** Consumers may produce events with `PENDING`. Old events may exist with `PENDING`.

---

### 5. Required ↔ Optional

#### Required → Optional (ALLOWED)

```json
// v1
{
  "name": "John"  // required
}

// v1 (compatible change)
{
  "name": "John" | null  // now optional
}
```

**Consumer behavior:** Consumers already expect the field. Making it optional is safe.

#### Optional → Required (BREAKING)

```json
// v1
{
  "name": "John" | null  // optional
}

// v2 (BREAKING - now required)
{
  "name": "John"  // required
}
```

**Why breaking:** Old events may have `null`. Consumers may not handle missing field.

---

## Version Bumping Guide

### When to Bump Major Version

- Remove any field
- Add required field
- Change field type
- Rename field
- Remove enum value
- Make optional field required

### When NOT to Bump

- Add optional field with default
- Make required field optional
- Add documentation/comments
- Internal implementation changes

---

## Consumer Implementation Guidelines

### Robust Field Access

```kotlin
// Good: Handle missing/null fields
val name = event.payload.name ?: "Unknown"
val count = event.payload.count ?: 0

// Bad: Assume field exists
val name = event.payload.name  // NPE risk
```

### Enum Handling

```kotlin
// Good: Handle unknown values
enum class Status { ACTIVE, INACTIVE }
fun parseStatus(value: String): Status =
    try { Status.valueOf(value) }
    catch (e: IllegalArgumentException) {
        log.warn("Unknown status: $value")
        Status.INACTIVE  // Safe default
    }
```

### Version Negotiation

```kotlin
fun handle(event: IntegrationEvent<*>) {
    when (event.version) {
        "1" -> handleV1(event)
        "2" -> handleV2(event)
        else -> log.warn("Unknown event version: ${event.version}")
    }
}
```

---

## Change Checklist

Before making event schema changes:

- [ ] Identify change type (allowed, caution, or breaking)
- [ ] If breaking, create new major version
- [ ] Update event documentation
- [ ] Notify consumer teams
- [ ] Deploy producers first (for additions)
- [ ] Deploy consumers (for removals)
- [ ] Monitor for errors
- [ ] Deprecate old version after migration

---

## Deprecation Policy

### Timeline

| Phase | Duration | Actions |
|-------|----------|---------|
| Announcement | Day 0 | Announce deprecation, provide migration guide |
| Warning Period | 90 days | Log warnings for old version usage |
| Sunset Period | 30 days | Reject new events with old version |
| Removal | Day 120 | Delete old version support |

### Deprecation Notice Format

```markdown
## Deprecation Notice

**Event:** `character.calculated.v1`
**Deprecated:** 2025-03-10
**Sunset:** 2025-06-10
**Replacement:** `character.calculated.v2`

**Migration:**
- Replace `characterClass` with `characterClassCode`
- `totalExpectedCost` is now `expectedCostMeso`
```

---

## References

- [Event Contract v1](./contract-v1.md) - Event structure and naming
- [Sample Events](./samples/) - Example event definitions
- [ADR-018](../01_ADR/ADR-018-strategy-pattern-for-acl.md) - Anti-Corruption Layer strategy
