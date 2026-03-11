package maple.expectation.infrastructure.mongodb

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service

/**
 * V5 CQRS Query Side Service - MongoDB Read Operations
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Fast read from CharacterValuationView collection
 *   <li>LogicExecutor pattern for exception handling
 *   <li>Micrometer metrics for monitoring
 *   <li>Graceful degradation on MongoDB failure
 * </ul>
 */
@Service
@ConditionalOnBean(MongoTemplate::class)
class CharacterViewQueryService(
    private val repository: CharacterValuationRepository,
    private val mongoTemplate: MongoTemplate,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(CharacterViewQueryService::class.java)

    /** Find character valuation view by user IGN (O(1) indexed lookup) */
    fun findByUserIgn(userIgn: String): CharacterValuationView? {
        val context = TaskContext.of("MongoQuery", "FindByUserIgn", userIgn)

        return executor.executeOrDefault(
            {
                val result = repository.findByUserIgn(userIgn)
                if (result != null) {
                    meterRegistry
                        .timer("mongodb.query.latency", "operation", "hit")
                        .record(Duration.ofMillis(1))
                    result
                } else {
                    meterRegistry
                        .timer("mongodb.query.latency", "operation", "miss")
                        .record(Duration.ofMillis(1))
                    null
                }
            },
            null,
            context,
        )
    }

    /**
     * Upsert character valuation view with optimistic locking (Unit 5: Batch-Realtime Race Condition Fix)
     *
     * <h3>Optimistic Locking Strategy</h3>
     *
     * <ul>
     *   <li>Uses version field to prevent lost updates
     *   <li>Conditional update: Only updates if version matches expected value
     *   <li>Auto-increment: Version is incremented on successful update
     *   <li>Metrics: Tracks skipped updates due to version mismatch
     * </ul>
     *
     * <h3>Update Logic</h3>
     *
     * <pre>
     * 1. If document exists with matching messageId:
     *    a. Check if incoming version > current version
     *    b. If yes: Update and increment version
     *    c. If no: Skip update (realtime update wins over batch)
     * 2. If document doesn't exist:
     *    a. Insert with version = 1
     * </pre>
     *
     * <h3>Batch vs Realtime Priority</h3>
     *
     * <p>Realtime updates (higher version) take precedence over batch updates (lower version).
     * This prevents batch jobs from overwriting fresh realtime data.
     *
     * @param view The view to upsert with version information
     */
    fun upsert(view: CharacterValuationView) {
        val context = TaskContext.of("MongoQuery", "UpsertWithOptimisticLock", view.userIgn)

        executor.executeVoid(
            {
                // First, try to find existing document by messageId
                val documentId = view.id ?: view.messageId
                val existing = if (documentId != null) repository.findById(documentId).orElse(null) else null

                if (existing != null) {
                    // Document exists - apply optimistic locking
                    handleUpdateWithOptimisticLock(view, existing)
                } else {
                    // Document doesn't exist - insert with version = 1
                    handleInsertNew(view)
                }
            },
            context,
        )
    }

    /**
     * Handle update with optimistic locking check.
     *
     * <p>Only updates if incoming version is greater than current version.
     * This ensures realtime updates (higher version) win over batch (lower version).
     */
    private fun handleUpdateWithOptimisticLock(incoming: CharacterValuationView, existing: CharacterValuationView) {
        val incomingVersion = incoming.version ?: 0L
        val currentVersion = existing.version ?: 0L

        if (incomingVersion > currentVersion) {
            // Incoming update is newer - apply update and increment version
            val query = Query(Criteria.where("id").`is`(existing.id))
            val update = Update()
                .set("userIgn", incoming.userIgn)
                .set("characterOcid", incoming.characterOcid)
                .set("characterClass", incoming.characterClass)
                .set("characterLevel", incoming.characterLevel)
                .set("totalExpectedCost", incoming.totalExpectedCost)
                .set("maxPresetNo", incoming.maxPresetNo)
                .set("calculatedAt", incoming.calculatedAt)
                .set("lastApiSyncAt", incoming.lastApiSyncAt)
                .set("version", incomingVersion + 1) // Increment version
                .set("lastAppliedVersion", incomingVersion) // Update lastAppliedVersion for event ordering
                .set("fromCache", incoming.fromCache)
                .set("presets", incoming.presets)

            val result = mongoTemplate.updateFirst(query, update, CharacterValuationView::class.java)

            if (result.modifiedCount > 0) {
                meterRegistry.counter("mongodb.optimistic_lock.updated").increment()
                log.debug(
                    "[OptimisticLock] Updated document: userIgn={}, version={}->{}",
                    incoming.userIgn,
                    currentVersion,
                    incomingVersion + 1,
                )
            } else {
                meterRegistry.counter("mongodb.optimistic_lock.skipped").increment()
                log.debug(
                    "[OptimisticLock] Skipped update (no modification): userIgn={}, version={}",
                    incoming.userIgn,
                    incomingVersion,
                )
            }
        } else {
            // Incoming update is older or same - skip to preserve realtime data
            meterRegistry.counter("mongodb.optimistic_lock.skipped").increment()
            log.debug(
                "[OptimisticLock] Skipped update (version too old): userIgn={}, incoming={}, current={}",
                incoming.userIgn,
                incomingVersion,
                currentVersion,
            )
        }
    }

    /**
     * Handle insert of new document with initial version.
     */
    private fun handleInsertNew(view: CharacterValuationView) {
        val newView = view.copy(
            version = 1L, // Initial version for new documents
            lastAppliedVersion = view.version ?: 1L, // Set lastAppliedVersion for event ordering
            id = view.id ?: view.messageId, // Use messageId as ID if not set
        )

        repository.save(newView)
        meterRegistry.counter("mongodb.optimistic_lock.inserted").increment()
        log.debug(
            "[OptimisticLock] Inserted new document: userIgn={}, version=1, lastAppliedVersion={}",
            view.userIgn,
            view.version,
        )
    }

    /** Delete by user IGN (for invalidation) */
    fun deleteByUserIgn(userIgn: String) {
        val context = TaskContext.of("MongoQuery", "Delete", userIgn)

        executor.executeVoid(
            {
                repository.deleteByUserIgn(userIgn)
            },
            context,
        )
    }

    /** Delete all documents (for testing) */
    fun deleteAll() {
        val context = TaskContext.of("MongoQuery", "DeleteAll", "all")

        executor.executeVoid(
            {
                repository.deleteAll()
            },
            context,
        )
    }

    /** Count all documents for a specific user IGN */
    fun countByUserIgn(userIgn: String): Long {
        val context = TaskContext.of("MongoQuery", "Count", userIgn)

        return executor.executeOrDefault(
            { if (repository.findByUserIgn(userIgn) != null) 1L else 0L },
            0L,
            context,
        )
    }

    /**
     * Get the last applied event version for a user (Unit 4: Event Ordering & Versioning)
     *
     * <p>Used by MongoDBSyncWorker to determine if an event should be applied or buffered.
     *
     * <h3>Event Ordering Logic</h3>
     *
     * <ul>
     *   <li>If event.version <= lastAppliedVersion: Skip (already applied)
     *   <li>If event.version == lastAppliedVersion + 1: Apply immediately (next expected)
     *   <li>If event.version > lastAppliedVersion + 1: Buffer (out-of-order, waiting for gap)
     * </ul>
     *
     * @param userIgn User in-game name
     * @return Last applied version, or 0L if no document exists
     */
    fun getLastAppliedVersion(userIgn: String): Long {
        val context = TaskContext.of("MongoQuery", "GetLastAppliedVersion", userIgn)

        return executor.executeOrDefault(
            {
                val view = repository.findByUserIgn(userIgn)
                view?.lastAppliedVersion ?: 0L
            },
            0L,
            context,
        )
    }
}
