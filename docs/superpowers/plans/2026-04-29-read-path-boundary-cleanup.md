# Plan: Read Path V5 Boundary Cleanup

**Date**: 2026-04-29
**Scope**: V5 Controller + dead FanOut/remove deleteByUserIgn vertical slice
**ADR Reference**: `docs/01_ADR/ADR-three-path-independence-mq-boundary.md` Phase 2
**Issue**: #758

## Goal

V5 Read Path에서 ADR 경계 위반 2건을 제거하여 Read Path가 "HTTP + 조회 + enqueue only" 원칙을 완전히 준수하도록 한다. 동시에 미사용 코드(FanOut port/adapter, deleteByUserIgn vertical slice)를 완전히 제거한다.

## Current Violations

| # | Violation | Location | Severity |
|---|-----------|----------|----------|
| 1 | Pre-warm: `EquipmentFanOutPort.preFetchByOcid()` triggers Nexon API call chain | V5 Controller 113-163 | Medium |
| 2 | Recalculate: `queryPort.deleteByUserIgn()` performs DELETE on view table | V5 Controller 186 | Low |

## Dead Code to Remove

| # | Dead Code | Why Dead |
|---|-----------|----------|
| 3 | `EquipmentFanOutPort` in Write Path Workers (`AbstractExpectationCalcWorker` 등) | `preWarmBatch()` is no-op, field unused |
| 4 | `deleteByUserIgn()` entire vertical slice | V5 is the only caller; removing V5 usage makes it dead |
| 5 | `EquipmentFanOutPort` interface + `NexonEquipmentMicroBatchAdapter` | After removing from V5 + Workers, no consumers remain |

## Tasks

### Task 1: Remove Pre-Warm from V5 Controller

**What**: Remove all pre-warm related code from `GameCharacterControllerV5`.

**How**:
1. Remove `fanOutPort: EquipmentFanOutPort` constructor parameter
2. Remove `@Value("\${fanout.enabled:false}") fanOutEnabled: Boolean`
3. Remove `@Qualifier("asyncExecutor") preWarmExecutor: Executor`
4. Remove `preWarmSemaphore`, `preWarmFailureCounter`, `preWarmRejectedCounter` fields
5. Remove `preWarmEquipmentCache()` private method
6. Remove pre-warm block (lines 113-128) from `processPostgreSQLCacheFirstLookup()`
7. Remove `MeterRegistry` constructor parameter (only used for pre-warm counters)
8. Clean up unused imports

**Files**: `module-web/.../controller/v5/GameCharacterControllerV5.kt`

### Task 2: Remove Recalculate DELETE from V5 Controller

**What**: Remove `queryPort.deleteByUserIgn()` call. Keep `force=true` queue submission only.

**How**:
1. Remove `executorPort.executeVoidJava({ queryPort.deleteByUserIgn(userIgn) }, context)` from `processCacheInvalidation()`
2. Keep `queueCalculationTask(userIgn, true, presetNo, context)` — `force=true` ensures Write Path overwrites
3. Simplify `processCacheInvalidation()` — may inline to `queueCalculationTask` call

**Files**: `module-web/.../controller/v5/GameCharacterControllerV5.kt`

### Task 3: Remove Dead `EquipmentFanOutPort` from Write Path Workers

**What**: Remove unused `EquipmentFanOutPort` injection from worker hierarchy.

**How**:
1. Remove `equipmentFanOutPort: EquipmentFanOutPort` from `AbstractExpectationCalcWorker` constructor
2. Remove `@Qualifier("asyncExecutor") preWarmExecutor: Executor` from `AbstractExpectationCalcWorker` constructor
3. Remove `preWarmBatch()` no-op method
4. Update `ExpectationCalcWorker` and `ExpectationCalcLowWorker` constructors to match

**Files**: `module-infra/.../worker/AbstractExpectationCalcWorker.kt`, `ExpectationCalcWorker.kt`, `ExpectationCalcLowWorker.kt`

### Task 4: Delete `EquipmentFanOutPort` Interface + Adapter

**What**: After Tasks 1+3 remove all consumers, delete the port and its sole implementation.

**How**:
1. Delete `module-core/.../port/out/EquipmentFanOutPort.kt`
2. Delete `module-infra/.../adapter/outgoing/NexonEquipmentMicroBatchAdapter.kt`
3. Search for any remaining references — should be zero

**Files**: 2 files deleted

### Task 5: Remove `deleteByUserIgn()` Vertical Slice

**What**: Remove the entire `deleteByUserIgn()` method chain since V5 is the only caller.

**How**:
1. Remove `deleteByUserIgn()` from `CharacterViewQueryPort` interface
2. Remove `deleteByUserIgn()` from `CharacterViewQueryPortAdapter`
3. Remove `deleteByUserIgn()` from `CharacterViewQueryServicePostgres`
4. Remove `deleteByUserIgn()` from `CharacterValuationJpaRepository`
5. Remove `deleteByUserIgn()` from `CharacterValuationRepositoryImpl`
6. Remove from `CharacterValuationViewJpaRepository` if present

**Files**: port interface, adapter, service, JPA repos in module-core + module-infra

### Task 6: Update Tests

**What**: Remove/update tests for deleted functionality.

**module-web test** (`GameCharacterControllerV5Test.kt`):
1. Remove `FakeEquipmentFanOutPort` inner class
2. Remove `getExpectationV5_fanOutEnabled_triggersPrewarm` test
3. Remove `getExpectationV5_fanOutDisabled_noPrewarm` test
4. Remove `recalculateExpectationV5_deletesCacheAndEnqueuesForce` test
5. Update `setUp()` — remove `fanOutPort`, `preWarmExecutor`, `meterRegistry` setup

**module-app test** (`GameCharacterControllerV5Test.java`):
1. Remove `testForceRecalculation_DeletesCacheAndQueues` test
2. Remove `testForceRecalculationQueueFull_Returns503` test
3. Remove `TestableGameCharacterControllerV5.recalculateExpectationV5Internal()` helper

**module-infra tests** (if `deleteByUserIgn` tests exist):
1. Remove `deleteByUserIgn` test cases from `CharacterViewQueryServicePostgresTest`
2. Remove `deleteByUserIgn` test cases from `CharacterViewQueryPortAdapterTest`

**Files**: 2-4 test files

### Task 7: Verification

1. Compile: `./gradlew compileKotlin compileJava --continue`
2. Test: `./gradlew test`
3. Server runtime: `set -a && source .env && set +a && ./gradlew :module-app:bootRun` → curl V5 expectation endpoint
4. Verify: no `EquipmentFanOutPort`, `NexonApiClient`, `deleteByUserIgn` references in module-web

## Out of Scope

- V4 Controller migration (legacy, separate effort)
- EquipmentExpectationServiceV4 decomposition
- module-read physical separation (Phase 3)
- PopularCharacterWarmupScheduler cleanup (separate concern, uses CacheWarmupPort)
