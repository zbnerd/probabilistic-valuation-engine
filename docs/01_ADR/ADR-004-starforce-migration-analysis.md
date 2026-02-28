# ADR-004 Phase 1: Starforce Domain Migration Analysis

## Date: 2026-02-28
## Status: Analysis Complete
## Related: ADR-004 Module-Core Migration

## Current State

### Files in module-app/src/main/java/maple/expectation/service/v2/starforce/

1. **StarforceLookupTable.java** (Interface)
   - Pure business interface with 9 methods
   - No dependencies
   - ✅ **Candidate for migration**

2. **StarforceLookupTableImpl.java** (Implementation)
   - Contains Markov chain calculation logic
   - Dependencies:
     - `LogicExecutor` (infrastructure.executor)
     - `@Component` annotation
     - `ConcurrentHashMap` for caching
     - `AtomicBoolean` for initialization state
   - ⚠️ **Partial migration needed** - pure calculation methods can be extracted

3. **config/NoljangProbabilityTable.java** (Utility)
   - Pure static utility class
   - No external dependencies
   - ✅ **Candidate for migration**

### Existing Port in module-core

**StarforceLookupPort.kt** exists at:
```
module-core/src/main/kotlin/maple/expectation/core/calculator/port/StarforceLookupPort.kt
```

Current methods:
- `getExpectedCost(currentStar, targetStar, itemLevel): BigDecimal`
- `getMaxStarForLevel(itemLevel: Int): Int`

### Dependencies Analysis

#### Used by (in module-app):
1. `EquipmentExpectationServiceV4.java`
2. `LookupTableInitializer.java` (ApplicationRunner)
3. `StarforceDecoratorV4.java`
4. `PresetCalculationHelper.java`
5. `StarforceApplicationService.java`

## Migration Strategy

### Phase 1: Port Interface Expansion (module-core)

**File**: `module-core/src/main/kotlin/maple/expectation/core/calculator/port/StarforceLookupPort.kt`

Add missing methods from `StarforceLookupTable.java`:
```kotlin
interface StarforceLookupPort {
    // Existing
    fun getExpectedCost(currentStar: Int, targetStar: Int, itemLevel: Int): BigDecimal
    fun getMaxStarForLevel(itemLevel: Int): Int

    // New methods to add
    fun getSuccessProbability(currentStar: Int): BigDecimal
    fun getDestroyProbability(currentStar: Int): BigDecimal
    fun getSingleEnhanceCost(currentStar: Int, itemLevel: Int): BigDecimal
    fun getExpectedCost(
        currentStar: Int,
        targetStar: Int,
        itemLevel: Int,
        useStarCatch: Boolean,
        useSundayMaple: Boolean,
        useDiscount: Boolean,
        useDestroyPrevention: Boolean
    ): BigDecimal
    fun getExpectedDestroyCount(
        currentStar: Int,
        targetStar: Int,
        useStarCatch: Boolean,
        useSundayMaple: Boolean,
        useDestroyPrevention: Boolean
    ): BigDecimal
    fun initialize()
    fun isInitialized(): Boolean
}
```

### Phase 2: Pure Logic Domain (module-core)

**Create**: `module-core/src/main/kotlin/maple/expectation/core/starforce/domain/`

1. **StarforceCalculationEngine.kt** - Pure calculation logic
   - Extract calculation methods from `StarforceLookupTableImpl`
   - Remove LogicExecutor dependency
   - Remove caching (infrastructure concern)
   - Remove @Component annotation
   - Keep all Markov chain algorithms

2. **NoljangProbabilityCalculator.kt** - Migrate utility class
   - Convert `NoljangProbabilityTable.java` to Kotlin
   - Keep static methods
   - Pure calculation logic

3. **StarforceConstants.kt** - Extract constants
   - Success rates table
   - Destroy rates table
   - Cost divisors
   - Level limits

### Phase 3: Infrastructure Adapter (module-app)

**Modify**: `module-app/.../starforce/StarforceLookupTableImpl.java`

- Keep as adapter that implements `StarforceLookupPort`
- Maintain LogicExecutor for exception handling
- Maintain ConcurrentHashMap for caching
- Delegate calculation to `StarforceCalculationEngine`

## Implementation Details

### Pure Logic Extraction

The following methods are **pure business logic** (no infrastructure dependencies):

From `StarforceLookupTableImpl.java`:
- `getMaxStarForLevel(int)` - pure lookup
- `computeMarkovExpectedCost(...)` - pure algorithm
- `getStageParams(...)` - pure calculation
- `applyStarCatch(...)` - pure math
- `getSingleEnhanceCostRaw(...)` - pure math
- `roundToNearest10(double)` - pure utility
- `validateStarRange(...)` - pure validation
- `cacheKey(...)` - pure string formatting

### Infrastructure Concerns (stay in module-app)

- `LogicExecutor` - exception handling
- `ConcurrentHashMap` - caching
- `AtomicBoolean` - initialization state
- `@Component` - Spring bean
- `initialize()` - lifecycle management
- `precomputeTables()` - cache warming

## Dependency Direction

```
module-app (adapter)
    ↓ depends on
module-core (pure domain + port)
    ↑ implements
StarforceLookupPort (interface)
```

## Migration Checklist

- [ ] Phase 1: Expand StarforceLookupPort interface
- [ ] Phase 2: Create StarforceCalculationEngine in module-core
- [ ] Phase 2: Migrate NoljangProbabilityTable to Kotlin in module-core
- [ ] Phase 2: Extract constants to StarforceConstants
- [ ] Phase 3: Refactor StarforceLookupTableImpl to use core engine
- [ ] Verify: All tests pass
- [ ] Verify: ArchUnit validation passes
- [ ] Verify: No circular dependencies

## Risk Assessment

| Risk | Level | Mitigation |
|------|-------|------------|
| Breaking existing callers | Low | Port interface remains compatible |
| Calculation errors | Medium | Comprehensive unit tests |
| Performance regression | Low | Caching stays in adapter |
| Circular dependencies | Low | Core has no dependencies on app |

## Next Steps

1. Implement Phase 1 (Port expansion)
2. Implement Phase 2 (Domain migration)
3. Implement Phase 3 (Adapter refactoring)
4. Run full test suite
5. Update documentation
