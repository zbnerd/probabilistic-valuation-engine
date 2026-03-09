# Spring Batch Implementation Findings

**Date**: 2026-03-08
**Task**: Task #11 - Verification: Spring Batch Implementation Scope
**Agent**: spring-batch-explorer

---

## Executive Summary

Spring Batch **is actively used** in this project for the equipment refresh batch job (Issue #356). However, the P1-9 and P2-19 concerns from the tech debt analysis **do not directly apply** to the current Spring Batch implementation because:

1. **Spring Batch does NOT update MongoDB directly** - it only writes to an in-memory Priority Queue
2. **MongoDB updates are handled by separate event handlers** (MongoDBSyncWorker)
3. **The batch job already uses unique JobParameters** (timestamp) for restart capability
4. **State initialization is already implemented** (ADR-084 fix)

---

## Usage Status

**Is Spring Batch used?** ✅ **YES**

### Specific Jobs Found:

1. **equipmentRefreshJob** (Main Batch Job)
   - **Location**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/BatchConfig.kt`
   - **Purpose**: Daily refresh of all user equipment data
   - **Schedule**: Every day at 2 AM (cron: `0 0 2 * * *`)
   - **Scheduler**: `BatchScheduler.kt`

2. **MonitoringReportJob** (Monitoring Batch Job)
   - **Location**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/MonitoringReportJob.kt`
   - **Purpose**: Scheduled monitoring reports
   - **Uses**: LockStrategy for distributed safety

### Components Found:

| Component | Location | Purpose |
|-----------|----------|---------|
| `BatchConfig` | `module-infra/.../config/BatchConfig.kt` | Job & Step configuration |
| `BatchScheduler` | `module-infra/.../batch/BatchScheduler.kt` | Scheduled execution (cron) |
| `OcidReader` | `module-infra/.../batch/reader/OcidReader.kt` | Reads OCIDs from MySQL |
| `LowPriorityQueueWriter` | `module-infra/.../batch/writer/LowPriorityQueueWriter.kt` | Writes to Priority Queue |
| `BatchMetricsLogger` | `module-infra/.../batch/listener/BatchMetricsLogger.kt` | Metrics tracking |

---

## Data Flow Analysis

### Current Architecture:

```mermaid
flowchart LR
    A[BatchScheduler<br/>@Scheduled 2AM] --> B[Spring Batch Job]
    B --> C[OcidReader<br/>Read OCIDs from MySQL]
    C --> D[LowPriorityQueueWriter<br/>Write to Priority Queue]
    D --> E[PriorityCalculationQueue<br/>In-Memory Queue]
    E --> F[ExpectationCalculationWorker<br/>Processes Queue Tasks]
    F --> G[MySQL: Write Results]
    F --> H[Redis Streams: Publish Events]
    H --> I[MongoDBSyncWorker<br/>Event Handler]
    I --> J[MongoDB: Update Read Model]
```

### Key Finding:

**Spring Batch does NOT write to MongoDB!**

- Spring Batch (`LowPriorityQueueWriter`) only adds tasks to `PriorityCalculationQueue`
- MongoDB updates are performed by `MongoDBSyncWorker` (separate event consumer)
- This is a **CQRS separation**: Batch → Command (MySQL/Queue) → Event → Query (MongoDB)

---

## Race Condition Risks (P1-9)

### P1-9 Original Concern:
> "Spring Batch로 Read Model을 주기적으로 재구축하면서, 동시에 실시간 이벤트 컨슈머가 동일 MongoDB Read Model을 업데이트하면, 배치가 이전 상태로 실시간 업데이트를 덮어쓸 수 있다."

### Actual Risk Assessment:

**Risk Level: ✅ NOT APPLICABLE**

**Reason:**
1. Spring Batch never writes to MongoDB directly
2. MongoDB is only updated via event handlers (`MongoDBSyncWorker`)
3. The actual flow is: `Batch → Queue → Worker → MySQL → Event → MongoDB Sync`

### Where Race Conditions CAN Occur:

**Location**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/CharacterViewQueryService.kt`

**Risk**: Concurrent updates to MongoDB `CharacterValuationView` from:
1. Real-time user requests (via calculation → event → sync)
2. Potential batch-derived events (same path)

**Current Mitigation**: 
- Uses `messageId` for idempotency (line 66)
- Uses MongoDB `upsert` operation (line 80)
- **NO version field or optimistic locking** (potential improvement area)

### Recommended Improvements:

1. **Add Version Field to CharacterValuationView**:
   ```kotlin
   @Document(collection = "character_valuation_view")
   data class CharacterValuationView(
       @Id id: String? = null,
       val userIgn: String,
       val version: Long = 0,  // Add this
       // ... other fields
   )
   ```

2. **Conditional Update in CharacterViewQueryService**:
   ```kotlin
   fun upsert(view: CharacterValuationView) {
       val query = Query(Criteria.where("messageId").`is`(view.messageId)
           .and("version").lt(view.version))  // Only update if newer
       // ... rest of upsert logic
   }
   ```

---

## Files to Modify for P1-9 (Optimistic Locking)

Since Spring Batch doesn't write to MongoDB, P1-9 fixes should be in the **MongoDB sync layer**:

| Priority | File | Modification |
|----------|------|--------------|
| P1 | `module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/CharacterValuationView.kt` | Add `version` field |
| P1 | `module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/CharacterViewQueryService.kt` | Implement conditional update logic |
| P2 | `module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/CharacterValuationRepository.kt` | Add version-based query methods |

---

## Restart Configuration (P2-19)

### P2-19 Original Concern:
> "프로세스가 강제 종료(kill -9)되면, JobRepository에 상태가 STARTED로 남아 재실행이 차단된다."

### Current Implementation Status: ✅ **ALREADY ADDRESSED**

**Evidence:**

1. **Unique JobParameters** (Already Implemented):
   ```kotlin
   // BatchScheduler.kt line 59-60
   val params = JobParametersBuilder()
       .addLong("timestamp", System.currentTimeMillis())  // Unique per execution
       .toJobParameters()
   ```

2. **State Initialization** (ADR-084 Fix - Completed):
   ```kotlin
   // OcidReader.kt line 74-79
   @BeforeStep
   fun initializeState(stepExecution: StepExecution) {
       this.ocidIterator = null
       this.currentPage = 0
       this.hasNextPage = true
   }
   ```

### Verification:
- ✅ Uses unique `timestamp` parameter for each execution
- ✅ `@BeforeStep` hook ensures state is reset
- ✅ No `COMPLETED` job instance blocking (new timestamp = new instance)

### No Additional Changes Required for P2-19

---

## Recommendations

### High Priority:

1. **Document the Architecture Separation**
   - Create a diagram showing Spring Batch → Queue → Event → MongoDB separation
   - Update ADR-082 to clarify that Batch doesn't directly update MongoDB

2. **Add MongoDB Version Field** (For true P1-9 fix)
   - Add `version` field to `CharacterValuationView`
   - Implement conditional updates in `CharacterViewQueryService`
   - This protects against concurrent updates from ANY source (not just batch)

### Medium Priority:

3. **Enhance Monitoring**
   - Add metrics for MongoDB version conflicts (if optimistic locking is added)
   - Track batch → queue → event → MongoDB latency

4. **Consider Separate Batch Collection**
   - If batch rebuilds are needed, use a separate MongoDB collection
   - Swap atomically after completion (dual-write pattern)

### Low Priority:

5. **Review P1-9/P2-19 Classification**
   - Update tech debt document to reflect actual architecture
   - These concerns were valid but apply to different components than originally thought

---

## Conclusion

**Spring Batch is well-implemented** with:
- ✅ Proper restart configuration (unique JobParameters)
- ✅ State initialization (ADR-084 fix)
- ✅ Clean separation from MongoDB writes
- ✅ Integration with V5 Priority Queue

**P1-9/P2-19 concerns** should be addressed in:
- **MongoDB sync layer** (CharacterViewQueryService) - not Spring Batch
- Add version field + conditional updates for true race condition protection

**No immediate Spring Batch code changes required** for P1-9 or P2-19.

---

## Related Documents

- [ADR-082: Spring Batch Implementation](../01_ADR/ADR-082-issue-356-batch-refresh.md)
- [ADR-084: OcidReader State Fix](../01_ADR/ADR-084-ocidreader-data-loss-state-fix.md)
- [Tech Debt Analysis](./project_tech_debt260308.md)
- [Code Review Implementation Plan](./2026-03-06-code-review-implementation-plan.md)
