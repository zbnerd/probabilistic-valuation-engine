# ADR-348: Fix Issue #644 - God Object: ExpectationCacheCoordinator Decomposition

## Date
2025-03-30

## Status
Proposed

## Context
[P1][Architecture] Issue #644 identifies that `ExpectationCacheCoordinator` violates the Single Responsibility Principle (SRP) by handling too many responsibilities. This makes the class difficult to test, maintain, and extend.

### Current Responsibilities

| Responsibility | Methods | Lines |
|----------------|---------|-------|
| Compression/Serialization | `compressAndSerialize()`, `decompressInternal()`, `compressToGzipBytes()` | ~30 |
| Cache Value Conversion | `convertCachedValueToBase64()`, `convertCachedValueToGzipBytes()` | ~40 |
| Response Building | `rebuildWithCacheFlag()` | ~10 |
| Metrics Recording | `recordFastPathHit()`, `recordFastPathMiss()` | ~5 |
| Admission Control | `executeCalculatorWithAdmission()` | ~40 |
| Core Orchestration | `getOrCalculate()`, `getGzipOrCalculate()`, `getGzipFromL1CacheDirect()` | ~100 |
| Calculator Execution | `executeCalculator()` | ~10 |
| Error Handling | `decompressCachedResponse()` | ~15 |

**Total**: ~483 lines with 7 distinct responsibilities

## Decision

### Phase 1: Extract Compression Service

**1.1 Create ExpectationCacheCompressionService**
- Location: `module-app/src/main/java/maple/expectation/application/service/expectation/cache/`
- Responsibilities:
  - `compressAndSerialize()` - Response → JSON → GZIP → Base64
  - `compressToGzipBytes()` - Response → JSON → GZIP bytes
  - `decompress()` - Base64 → GZIP → JSON → Response
- Dependencies: `ObjectMapper`, `GzipUtils`

**1.2 Update ExpectationCacheCoordinator**
- Inject `ExpectationCacheCompressionService`
- Replace direct compression calls with service calls

### Phase 2: Extract Value Converter

**2.1 Create CacheValueConverter**
- Location: `module-app/src/main/java/maple/expectation/application/service/expectation/cache/`
- Responsibilities:
  - `convertCachedValueToBase64()` - Handle byte[] → String migration
  - `convertCachedValueToGzipBytes()` - L1 cache value conversion
  - `unwrapValueWrapper()` - Handle Spring's SimpleValueWrapper
- Handles legacy format migration transparently

### Phase 3: Extract Response Builder

**3.1 Create CachedResponseBuilder**
- Location: `module-app/src/main/java/maple/expectation/application/service/expectation/cache/`
- Responsibilities:
  - `buildWithCacheFlag()` - Sets fromCache=true
  - Preserves all original response fields

### Phase 4: Simplify Coordinator

**4.1 Update ExpectationCacheCoordinator**
- Remove extracted methods
- Keep only orchestration logic:
  - `getOrCalculate()` - Main singleflight pattern
  - `getGzipOrCalculate()` - GZIP variant
  - `getGzipFromL1CacheDirect()` - L1 fast path
  - `executeCalculator()` - Calculator execution
  - `executeCalculatorWithAdmission()` - Admission control

## Class Structure After Refactoring

```
ExpectationCacheCoordinator (orchestration only)
├── ExpectationCacheCompressionService (compression)
├── CacheValueConverter (format conversion)
├── CachedResponseBuilder (response building)
├── CacheManagerPort (cache access)
├── ExecutorPort (task execution)
└── GlobalAdmissionControl (admission control)
```

## Consequences

### Positive
- Each class has a single, well-defined responsibility
- Easier to unit test (can mock compression/conversion)
- Clear separation of concerns
- Easier to extend (e.g., add new compression format)

### Negative
- More files to maintain
- Need to inject more dependencies
- Slightly more indirection

## Implementation Order

1. Create `ExpectationCacheCompressionService`
2. Create `CacheValueConverter`
3. Create `CachedResponseBuilder`
4. Update `ExpectationCacheCoordinator` to use new services
5. Run tests to verify behavior unchanged
6. Update ADR status to Accepted

## Related Issues
- Issue #644: God Object decomposition (this issue)
- Issue #640: CacheCoordinator Port extraction
- ADR-005: Hexagonal Architecture
