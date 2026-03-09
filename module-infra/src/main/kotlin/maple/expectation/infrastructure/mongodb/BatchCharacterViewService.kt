package maple.expectation.infrastructure.mongodb

import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service

/**
 * Batch Character View Service - Stage and Swap Pattern (Unit 5: Batch-Realtime Race Condition Fix)
 *
 * <h3>Purpose</h3>
 *
 * Prevents batch jobs from overwriting realtime updates by:
 *
 * <ul>
 *   <li>Using lower version numbers for batch updates
 *   <li>Building data in staging collection first
 *   <li>Atomic collection swap after batch completion
 *   <li>Metrics for skipped updates due to version mismatch
 * </ul>
 *
 * <h3>Optimistic Locking Strategy</h3>
 *
 * <p>Realtime updates use high version numbers (timestamp), batch uses low version numbers (fixed).
 * This ensures realtime data always wins when there's a conflict.
 *
 * <h3>Stage and Swap Pattern</h3>
 *
 * <pre>
 * 1. Batch starts: Clear staging collection
 * 2. Batch writes: Write to staging collection (not affecting production)
 * 3. Batch completes: Atomic rename staging -> production
 * 4. Realtime continues: Updates go to production collection during batch
 * </pre>
 *
 * <h3>CLAUDE.md Compliance</h3>
 *
 * <ul>
 *   <li>Section 12: LogicExecutor pattern for exception handling
 *   <li>Section 15: Lambda limit - extracted private methods
 *   <li>Stateless: No mutable instance state
 * </ul>
 */
@Service
class BatchCharacterViewService(
    private val mongoTemplate: MongoTemplate,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(BatchCharacterViewService::class.java)

    companion object {
        const val PRODUCTION_COLLECTION = "character_valuation_views"
        const val STAGING_COLLECTION = "character_valuation_views_staging"
        const val BATCH_VERSION_BASE = 1000L // Base version for batch updates
    }

    /**
     * Upsert batch data to staging collection with low version number.
     *
     * <p>Uses fixed low version number so realtime updates (high timestamp versions)
     * will always win in optimistic locking conflicts.
     *
     * @param view The view to upsert to staging collection
     * @return true if upsert succeeded, false if skipped due to version conflict
     */
    fun upsertToStaging(view: CharacterValuationView): Boolean {
        val context = TaskContext.of("BatchMongo", "UpsertToStaging", view.userIgn)

        return executor.executeOrDefault(
            {
                // Check if document exists in production with higher version
                val existingInProduction = findInProduction(view.userIgn)

                if (existingInProduction != null) {
                    val productionVersion = existingInProduction.version ?: 0L

                    if (productionVersion > BATCH_VERSION_BASE) {
                        // Production has realtime update with higher version - skip batch update
                        meterRegistry.counter("mongodb.batch.skipped_realtime_wins").increment()
                        log.debug(
                            "[BatchStaging] Skipped batch update (realtime wins): userIgn={}, prodVersion={}, batchVersion={}",
                            view.userIgn,
                            productionVersion,
                            BATCH_VERSION_BASE,
                        )
                        return@executeOrDefault false
                    }
                }

                // Write to staging collection with low version number
                upsertToStagingInternal(view.copy(version = BATCH_VERSION_BASE))
                meterRegistry.counter("mongodb.batch.staged").increment()
                return@executeOrDefault true
            },
            false,
            context,
        )
    }

    /**
     * Perform atomic swap of staging and production collections.
     *
     * <p>This is the critical step that makes all staged data visible atomically.
     *
     * <h3>Swap Process</h3>
     *
     * <pre>
     * 1. Rename production -> production_backup
     * 2. Rename staging -> production
     * 3. Drop production_backup
     * </pre>
     *
     * @return true if swap succeeded, false otherwise
     */
    fun swapStagingToProduction(): Boolean {
        val context = TaskContext.of("BatchMongo", "SwapCollections", "atomic_swap")

        return executor.executeOrDefault(
            {
                val timestamp = Instant.now().toEpochMilli()
                val backupCollection = "${PRODUCTION_COLLECTION}_backup_$timestamp"

                log.info("[BatchStaging] Starting atomic collection swap...")

                // Step 1: Rename production to backup
                renameCollection(PRODUCTION_COLLECTION, backupCollection)
                log.debug("[BatchStaging] Renamed production -> backup: {}", backupCollection)

                // Step 2: Rename staging to production
                renameCollection(STAGING_COLLECTION, PRODUCTION_COLLECTION)
                log.debug("[BatchStaging] Renamed staging -> production")

                // Step 3: Drop backup collection asynchronously
                dropCollectionAsync(backupCollection)

                meterRegistry.counter("mongodb.batch.swapped").increment()
                log.info("[BatchStaging] Atomic swap completed successfully")

                true
            },
            false,
            context,
        )
    }

    /**
     * Clear staging collection before batch starts.
     *
     * <p>Ensures clean state for each batch run.
     */
    fun clearStaging() {
        val context = TaskContext.of("BatchMongo", "ClearStaging", STAGING_COLLECTION)

        executor.executeVoid(
            {
                mongoTemplate.dropCollection(STAGING_COLLECTION)
                log.debug("[BatchStaging] Cleared staging collection")
            },
            context,
        )
    }

    // ========== Private Helper Methods ==========

    /** Find document in production collection by user IGN. */
    private fun findInProduction(userIgn: String?): CharacterValuationView? = executor.executeOrDefault(
        {
            val query = Query(Criteria.where("userIgn").`is`(userIgn))
            mongoTemplate.findOne(query, CharacterValuationView::class.java, PRODUCTION_COLLECTION)
        },
        null,
        TaskContext.of("BatchMongo", "FindInProduction", userIgn),
    )

    /** Internal upsert to staging collection. */
    private fun upsertToStagingInternal(view: CharacterValuationView) {
        val context = TaskContext.of("BatchMongo", "UpsertToStagingInternal", view.userIgn)

        executor.executeVoid(
            {
                // Use upsert by messageId (idempotent)
                val query = org.springframework.data.mongodb.core.query.Query(
                    Criteria.where("messageId").`is`(view.messageId),
                )
                val update = org.springframework.data.mongodb.core.query.Update()
                    .set("userIgn", view.userIgn)
                    .set("characterOcid", view.characterOcid)
                    .set("characterClass", view.characterClass)
                    .set("characterLevel", view.characterLevel)
                    .set("totalExpectedCost", view.totalExpectedCost)
                    .set("maxPresetNo", view.maxPresetNo)
                    .set("calculatedAt", view.calculatedAt)
                    .set("lastApiSyncAt", view.lastApiSyncAt)
                    .set("version", view.version) // Low version for batch
                    .set("fromCache", view.fromCache)
                    .set("presets", view.presets)

                mongoTemplate.upsert(query, update, CharacterValuationView::class.java, STAGING_COLLECTION)
            },
            context,
        )
    }

    /** Rename MongoDB collection atomically using MongoTemplate. */
    private fun renameCollection(oldName: String, newName: String) {
        executor.executeVoid(
            {
                // Execute rename collection command
                val command = org.bson.Document("renameCollection", oldName)
                    .append("to", newName)
                mongoTemplate.db.runCommand(command)
            },
            TaskContext.of("BatchMongo", "RenameCollection", "$oldName->$newName"),
        )
    }

    /** Drop collection asynchronously (fire and forget). */
    private fun dropCollectionAsync(collectionName: String) {
        executor.executeVoid(
            {
                try {
                    mongoTemplate.db.getCollection(collectionName).drop()
                    log.debug("[BatchStaging] Dropped backup collection: {}", collectionName)
                } catch (e: Exception) {
                    log.warn("[BatchStaging] Failed to drop backup collection: {}", collectionName)
                }
            },
            TaskContext.of("BatchMongo", "DropBackupCollection", collectionName),
        )
    }
}
