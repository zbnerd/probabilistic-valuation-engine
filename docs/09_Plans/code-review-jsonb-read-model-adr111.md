# Code Review Report: JSONB Read Model Plan (ADR-111)

**Reviewer:** Code Reviewer Agent
**Date:** 2026-04-19
**Plan File:** `/home/maple/.claude/plans/stateless-imagining-firefly.md`
**Branch:** `feature/jsonb-read-model` (proposed)
**Target:** PR to `develop`

---

## Executive Summary

**Files Reviewed:** 11 (5 new, 6 modified)
**Total Issues:** 12 (3 P0, 5 P1, 4 P2)

### By Severity
- **P0 (BLOCKING):** 3 - Must fix before merge
- **P1 (HIGH):** 5 - Should fix, could cause bugs
- **P2 (IMPROVEMENT):** 4 - Consider for future

### Recommendation: **REQUEST CHANGES**

P0 issues exist that violate SOLID principles (DIP) and create architectural debt.

---

## Stage 1: Spec Compliance

### Requirements Analysis
The plan proposes creating a new `character_read_model` table with a `response_json` JSONB column to store complete V5 responses, addressing the **data loss issue** where `ViewTransformer` discards cube/starforce/flame details.

**Spec Coverage:** ✅ PASS
- ✅ Addresses stated problem: "현재 V5 읽기 경로가 데이터 손실(lossy) 상태"
- ✅ 3-phase migration strategy defined
- ✅ Dual-write pattern specified
- ✅ Files listed with clear modification scope

**Missing/Extra:**
- ⚠️ **Missing**: Port interface signature change not specified - `findReadModelJsonByUserIgn()` returning `String` (raw JSON) violates DIP (see P0-1)
- ⚠️ **Extra**: Plan mentions "V4 ItemExpectationV4와 V5 ItemExpectationV5는 구조적으로 동일" - this is **INCORRECT** (see P1-2)

---

## Stage 2: Code Quality & Architecture

### P0 Issues (BLOCKING)

#### [P0-1] DIP VIOLATION: Port Returns Raw JSON String

**File:** Plan Step 8, `module-core/.../CharacterViewQueryPort.kt`

**Issue:** Proposed signature:
```kotlin
fun findReadModelJsonByUserIgn(userIgn: String): Optional<String>
```

**Root Cause:**
- Port (module-core) returns `String` (infrastructure concern)
- Web layer (module-web) would depend on JSON serialization format
- Tight coupling: If JSON structure changes, Port interface changes

**SOLID Violation:**
- **DIP (Dependency Inversion Principle):** High-level modules should not depend on low-level details. `String` is a low-level detail (raw JSON).
- **ISP (Interface Segregation Principle):** Port consumers are forced to handle raw JSON parsing.

**Fix:**
```kotlin
// Option 1: Return domain object (RECOMMENDED)
interface CharacterViewQueryPort {
    fun findByUserIgn(userIgn: String): Optional<CharacterView>
    fun findReadModelByUserIgn(userIgn: String): Optional<CharacterReadModel>  // NEW
}

// module-core: Create new domain model
data class CharacterReadModel(
    val userIgn: String,
    val responseJson: String,  // Internal to domain, encapsulated
    val calculatedAt: Instant,
    // ...
)

// Option 2: Use existing CharacterView with JSONB field extension
interface CharacterViewQueryPort {
    fun findByUserIgn(userIgn: String): Optional<CharacterView>
    // CharacterView already has presets: List<PresetView>?
    // Extend it to include raw JSON if needed for passthrough
}
```

**Justification:**
- Current `CharacterView` already abstracts JPA entity properly
- Adding a new method that returns raw `String` breaks this abstraction
- If V5 endpoint needs raw JSON for performance, add `CharacterView.getRawJson()` method instead

---

#### [P0-2] CRITICAL: V4→V5 DTO Structure Mismatch

**File:** Plan Step 6, `ViewTransformer.java`

**Plan Statement:**
> "V4 ItemExpectationV4와 V5 ItemExpectationV5는 **구조적으로 동일** (필드 1:1 매핑, 타입만 다름)"

**Reality:**

| Field | V4 (`ItemExpectationV4`) | V5 (`ItemExpectationV5`) |
|-------|-------------------------|------------------------|
| `potentialGrade` | `String?` (nullable) | `String` (non-null) |
| `additionalPotentialGrade` | `String?` (nullable) | `String` (non-null) |
| `expectedCost` | `Double` | `BigDecimal` |
| `expectedTrials` | `Double` | `BigDecimal` |
| All cost fields | `Double` | `BigDecimal` |

**Impact:**
- **NULLABILITY MISMATCH:** V4 has nullable fields that V5 doesn't
- **TYPE MISMATCH:** V4 uses `Double`, V5 uses `BigDecimal`
- `toV5ResponseJson()` will fail when V4 returns `null` for potential grades

**Fix:**
```java
// ViewTransformer.java - ADD null-safe mapping
private ItemExpectationV5 toItemV5(ItemExpectationV4 v4Item) {
    return new ItemExpectationV5(
        v4Item.getItemName(),
        v4Item.getItemIcon(),
        v4Item.getItemPart(),
        v4Item.getItemLevel(),
        BigDecimal.valueOf(v4Item.getExpectedCost()),  // NOT new BigDecimal(v4Item.getExpectedCost())!
        v4Item.getExpectedCostText(),
        toCostBreakdownV5(v4Item.getCostBreakdown()),
        v4Item.getEnhancePath(),
        v4Item.getPotentialGrade() != null ? v4Item.getPotentialGrade() : "",  // NULL SAFETY
        v4Item.getAdditionalPotentialGrade() != null ? v4Item.getAdditionalPotentialGrade() : "",
        v4Item.getCurrentStar(),
        v4Item.getTargetStar(),
        v4Item.getIsNoljang(),
        v4Item.getSpecialRingLevel(),
        toCubeExpectationV5(v4Item.getBlackCubeExpectation()),
        toCubeExpectationV5(v4Item.getAdditionalCubeExpectation()),
        toStarforceExpectationV5(v4Item.getStarforceExpectation()),
        toFlameExpectationV5(v4Item.getFlameExpectation())
    );
}
```

---

#### [P0-3] LOGIC EXECUTOR VIOLATION: Empty Catch Block Pattern

**File:** Plan Step 6, `ViewTransformer.java` (proposed `toV5ResponseJson`)

**Issue:** Plan does not specify LogicExecutor usage for the new `toV5ResponseJson()` method.

**Current Pattern (from `ViewTransformer.java:350-353`):**
```java
private <T> T parseSafely(ParseSupplier<T> supplier, T defaultValue) {
    return executor.executeOrDefault(
        supplier::get, defaultValue, TaskContext.of("ViewTransformer", "ParseSafely"));
}
```

**Required for `toV5ResponseJson()`:**
```java
public String toV5ResponseJson(String userIgn, EquipmentExpectationResponseV4 response) {
    return executor.executeOrDefault(
        () -> toV5ResponseJsonInternal(userIgn, response),
        "{}",  // Empty JSON object as fallback
        TaskContext.of("ViewTransformer", "ToV5ResponseJson", userIgn));
}

private String toV5ResponseJsonInternal(String userIgn, EquipmentExpectationResponseV4 response) throws Exception {
    EquipmentExpectationResponseV5 v5Response = convertToV5(response);
    return objectMapper.writeValueAsString(v5Response);
}
```

**Fix:** Add explicit LogicExecutor pattern in the plan.

---

### P1 Issues (HIGH)

#### [P1-1] Flyway Migration: Missing Index on `calculated_at`

**File:** Plan Step 2, `V111__character_read_model.sql`

**Current:**
```sql
CREATE INDEX idx_crm_response_json_gin ON character_read_model 
    USING GIN (response_json jsonb_path_ops);
```

**Issue:**
- Time-based queries (`ORDER BY calculated_at DESC`) are common in V5 read path
- No index on `calculated_at` for temporal queries
- GIN index on JSONB doesn't help with range scans on calculated_at

**Fix:**
```sql
-- ADD for time-series queries
CREATE INDEX idx_crm_calculated_at ON character_read_model (calculated_at DESC);

-- ADD for composite queries (user + time)
CREATE INDEX idx_crm_user_calculated ON character_read_model (user_ign, calculated_at DESC);
```

---

#### [P1-2] BigDecimal(Double) Constructor Anti-Pattern Risk

**File:** Plan Step 6, `ViewTransformer.java` conversion

**Plan Statement:**
> "V4 `Double` → V5 `BigDecimal` 변환 (`BigDecimal.valueOf()` 사용)"

**Issue:** Plan correctly specifies `BigDecimal.valueOf()`, but implementation is error-prone.

**Anti-Pattern (CLAUDE.md Line 119):**
```java
// BAD - introduces floating point error
new BigDecimal(0.1)  // → 0.10000000000000000555...
```

**Correct:**
```java
// GOOD - exact conversion
BigDecimal.valueOf(0.1)  // → 0.1
```

**Verification Required:** After implementation, search for:
```bash
grep -r "new BigDecimal(" module-app/src/main/java/maple/expectation/application/service/expectation/event/ViewTransformer.java
```

---

#### [P1-3] Transaction Boundary: Missing REQUIRES_NEW for Read Model Write

**File:** Plan Step 7, `EquipmentExpectationServiceV4.java`

**Issue:** Plan states "이중 쓰기 추가 (same TX)" but doesn't specify transaction propagation.

**Current Code (`EquipmentExpectationServiceV4.java:232-247`):**
```java
private void syncToViewTable(...) {
    CharacterViewQueryServicePostgres viewService = viewQueryServiceProvider.getIfAvailable();
    if (viewService == null) return;

    executor.executeVoidJava(() -> {
        var entity = viewTransformer.toEntityFromResponse(userIgn, character, response, taskId);
        viewService.upsert(entity);  // <-- Uses current TX
        log.debug("[ExpectationV4] Synced to view table: userIgn={}", userIgn);
    }, TaskContext.of("ExpectationV4", "SyncView", userIgn));
}
```

**Problem:**
- If main TX rolls back, view table write also rolls back
- Read model becomes inconsistent with write model

**Fix (Consider):**
```java
// In CharacterReadModelService.kt (new class)
@Transactional("transactionManager", propagation = Propagation.REQUIRES_NEW)
fun upsertReadModel(entity: CharacterReadModelEntity) {
    // Independent TX - survives main TX rollback
    repository.upsert(entity)
}
```

**Trade-off:**
- **PRO:** Read model always written, even if main TX fails
- **CON:** Performance cost (2 transactions instead of 1)
- **RECOMMENDATION:** For this use case, same TX is acceptable (best-effort cache)

---

#### [P1-4] Lambda Hell: V4→V5 Conversion

**File:** Plan Step 6, `ViewTransformer.java`

**Issue:** Plan doesn't specify private method extraction for conversion.

**Current Pattern (GOOD - `ViewTransformer.java:232-276`):**
```java
private PresetView toPresetView(PresetExpectation preset) { ... }  // Extracted
private CostBreakdownView toCostBreakdownView(CostBreakdownDto breakdown) { ... }
private List<ItemExpectationView> toItemViews(List<ItemExpectationV4> items) { ... }
```

**Required for V5 Conversion:**
```java
// DON'T do this (Lambda Hell):
public String toV5ResponseJson(...) {
    return executor.executeOrDefault(() -> {
        EquipmentExpectationResponseV5 v5 = new EquipmentExpectationResponseV5(
            response.getUserIgn(),
            // ... 20+ lines of inline conversion
        );
        return objectMapper.writeValueAsString(v5);
    }, "{}", context);
}

// DO this (Extracted):
public String toV5ResponseJson(...) {
    return executor.executeOrDefault(
        () -> toV5ResponseJsonInternal(userIgn, response),
        "{}", context);
}

private String toV5ResponseJsonInternal(...) throws Exception {
    EquipmentExpectationResponseV5 v5 = convertToV5(response);
    return objectMapper.writeValueAsString(v5);
}

private EquipmentExpectationResponseV5 convertToV5(EquipmentExpectationResponseV4 v4) {
    return new EquipmentExpectationResponseV5(
        v4.getUserIgn(),
        // ... conversion logic
    );
}
```

---

#### [P1-5] Test Coverage: Missing Edge Cases

**File:** Plan Step 10, `ViewTransformerV5JsonTest.java`

**Missing Test Cases:**
1. **Null fields in V4 response** → V5 conversion
2. **Empty presets list** → JSON serialization
3. **Double to BigDecimal precision** (e.g., 0.1 → 0.1, not 0.10000000000000000555)
4. **Korean text encoding** in JSON (userIgn with special characters)
5. **Large response** → JSON size limits

**Required Additional Tests:**
```java
@Test
@DisplayName("V4 null potentialGrade → V5 empty string")
void convertV4ToV5_NullPotentialGrade_ConvertsToEmptyString() {
    // Given
    ItemExpectationV4 v4Item = ItemExpectationV4.builder()
        .potentialGrade(null)  // V4 allows null
        .build();
    
    // When
    ItemExpectationV5 v5Item = transformer.toItemV5(v4Item);
    
    // Then
    assertThat(v5Item.getPotentialGrade()).isEqualTo("");  // V5 requires non-null
}

@Test
@DisplayName("Double to BigDecimal - No precision loss")
void convertDoubleToBigDecimal_NoPrecisionLoss() {
    // Given
    double v4Cost = 12345.67;
    
    // When
    BigDecimal v5Cost = BigDecimal.valueOf(v4Cost);
    
    // Then
    assertThat(v5Cost).isEqualTo("12345.67");  // Exact conversion
}
```

---

### P2 Issues (IMPROVEMENT)

#### [P2-1] Performance: JSON String Duplication

**File:** Plan Step 9, `GameCharacterControllerV5.kt`

**Issue:** Plan proposes:
```kotlin
val json = queryPort.findReadModelJsonByUserIgn(userIgn)
return ResponseEntity.ok(json)
```

**Problem:**
- JSON serialized twice: DB → String → HTTP response
- Jackson will parse and re-serialize (unnecessary CPU)

**Improvement:**
```kotlin
// Return raw String directly (Spring handles serialization)
// OR use @ResponseBody with custom converter
@GetMapping("/{userIgn}/expectation")
fun getExpectationV5(...): ResponseEntity<String> {
    val json = queryPort.findRawJsonByUserIgn(userIgn)
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(json)
}
```

---

#### [P2-2] Naming: `CharacterReadModel` vs `CharacterValuationViewEntity`

**File:** Multiple

**Issue:** Two similar entities exist:
- `CharacterValuationViewEntity` (current, lossy)
- `CharacterReadModelEntity` (proposed, lossless)

**Confusion:** Both serve "read model" purpose.

**Suggestion:**
- Rename current to `CharacterValuationSummaryEntity` (aggregated data)
- New name: `CharacterValuationDetailEntity` (full JSON)
- OR: Deprecate old table entirely (Phase 3)

---

#### [P2-3] Index Strategy: GIN jsonb_path_ops

**File:** Plan Step 2, `V111__character_read_model.sql`

**Current:**
```sql
CREATE INDEX idx_crm_response_json_gin ON character_read_model 
    USING GIN (response_json jsonb_path_ops);
```

**Analysis:**
- `jsonb_path_ops` is smaller (no indexing of every JSONB key/value)
- **GOOD:** For this use case (full document read, not queries on JSONB contents)
- **NO ACTION NEEDED** - just noting this is correct

---

#### [P2-4] Migration Version: V111 Conflicts

**File:** Plan Step 2

**Issue:** Plan uses `V111__character_read_model.sql`

**Current latest:** `V110__cache_storage_create_table.sql` (Apr 19, 2025)

**Status:** ✅ Correct (next sequential version)

**Reminder:** Ensure no concurrent PRs use V111.

---

## Positive Observations

1. ✅ **LogicExecutor Pattern:** Existing code properly uses LogicExecutor throughout
2. ✅ **@Slf4j Usage:** No `System.out.println` violations found in production code
3. ✅ **Lambda Prevention:** Current `ViewTransformer` properly extracts complex transformations
4. ✅ **BigDecimal.valueOf():** Plan correctly specifies safe conversion pattern
5. ✅ **Dual-Write Strategy:** Proper phased migration approach (Phase 1-3)
6. ✅ **ObjectProvider Pattern:** Correctly used for optional V5 dependency injection
7. ✅ **@ConditionalOnProperty:** V5 components properly gated on `v5.enabled`
8. ✅ **Transaction Manager:** Explicit `@Transactional("transactionManager")` used throughout

---

## Java-Kotlin Interop Analysis

### Current Code (GOOD)

**Kotlin Entity (`CharacterValuationViewEntity.kt`):**
- ✅ `@JvmStatic` companions where needed
- ✅ Data classes with proper nullability
- ✅ Nullable types marked with `?`

**Java Service (`ViewTransformer.java`):**
- ✅ Properly handles Kotlin nullable types
- ✅ Uses `@Nullable` annotation
- ✅ Safe navigation for optional fields

### Proposed Changes (NEEDS ATTENTION)

**V5 DTO (`EquipmentExpectationResponseV5.kt`):**
```kotlin
data class ItemExpectationV5(
    val potentialGrade: String,           // Non-null
    val additionalPotentialGrade: String, // Non-null
    // ...
)
```

**V4 DTO (`ItemExpectationV4.kt`):**
```kotlin
data class ItemExpectationV4(
    val potentialGrade: String?,           // NULLABLE
    val additionalPotentialGrade: String?, // NULLABLE
    // ...
)
```

**Required Conversion:**
```java
// ViewTransformer.java
private String nonNullGrade(String? nullableGrade) {
    return nullableGrade != null ? nullableGrade : "";
}
```

---

## SOLID Principles Assessment

### Single Responsibility Principle (SRP)
- ✅ `ViewTransformer`: Only transforms data
- ✅ `CharacterViewQueryServicePostgres`: Only queries
- ⚠️ `EquipmentExpectationServiceV4`: Handles both calculation AND view write (acceptable for Phase 1)

### Open/Closed Principle (OCP)
- ✅ Port interface allows extension
- ⚠️ New `findReadModelJsonByUserIgn()` modifies Port interface (breaking change)

### Liskov Substitution Principle (LSP)
- ✅ `CharacterViewQueryPortAdapter` properly implements Port
- ✅ `CharacterViewEntityAdapter` properly implements `CharacterView`

### Interface Segregation Principle (ISP)
- ❌ **P0-1:** `findReadModelJsonByUserIgn(): String` forces JSON handling on consumers

### Dependency Inversion Principle (DIP)
- ✅ Web layer depends on Port (module-core), not implementation
- ❌ **P0-1:** Returning raw `String` breaks abstraction

---

## Test Coverage Analysis

### Existing Tests (GOOD)

**ViewTransformerTest.java:**
- ✅ 189 lines, comprehensive coverage
- ✅ P1 decimal parsing tests
- ✅ Full transformation tests
- ✅ LogicExecutor mocking

**Missing for Plan:**
- ❌ V4→V5 conversion tests
- ❌ JSON serialization edge cases
- ❌ `CharacterReadModelService` upsert tests
- ❌ Integration test for dual-write path

---

## Security Review

✅ **No hardcoded secrets found**
✅ **No SQL injection risk** (JPA used, no string concatenation in queries)
✅ **No XSS risk** (read-only API, no user input reflected in HTML)

---

## Final Recommendation

### VERDICT: **REQUEST CHANGES**

**Blocking Issues (P0):**
1. **P0-1:** Fix Port interface - return domain object, not raw `String`
2. **P0-2:** Document V4→V5 structural mismatch and null-safety handling
3. **P0-3:** Specify LogicExecutor pattern for `toV5ResponseJson()`

**High Priority (P1):**
1. **P1-1:** Add `calculated_at` index for time-series queries
2. **P1-2:** Verify `BigDecimal.valueOf()` usage (not `new BigDecimal()`)
3. **P1-3:** Clarify transaction boundary (same TX vs REQUIRES_NEW)
4. **P1-4:** Extract V5 conversion to private methods
5. **P1-5:** Add edge case tests

### Approval Criteria

- [ ] P0-1: Port signature returns domain object, not raw `String`
- [ ] P0-2: V4→V5 conversion handles nullable fields correctly
- [ ] P0-3: LogicExecutor pattern documented in plan
- [ ] P1-1: Migration includes `calculated_at` index
- [ ] P1-5: Edge case tests added
- [ ] `./gradlew test` passes
- [ ] No `new BigDecimal(` constructor usage

---

## Reviewer Notes

This plan addresses a real data loss issue and follows a solid phased migration approach. The main concerns are:

1. **Architectural purity:** Returning raw JSON from Port violates DIP
2. **Type safety:** V4/V5 structural mismatch needs explicit handling
3. **Test coverage:** Edge cases for V4→V5 conversion

Once P0 issues are resolved, this will be a solid addition to the V5 CQRS architecture.

---

**Review completed:** 2026-04-19
**Reviewer:** Code Reviewer Agent (oh-my-claudecode:code-reviewer)
**Session:** a6c7eb149e1749dff
