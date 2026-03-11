package maple.expectation.infrastructure.persistence

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity
import maple.expectation.infrastructure.persistence.repository.CharacterValuationViewJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * V5 CQRS Query Side Service - PostgreSQL Read Operations
 *
 * MongoDB CharacterViewQueryService의 PostgreSQL 마이그레이션
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Fast read from character_valuation_views table
 *   <li>LogicExecutor pattern for exception handling
 *   <li>Micrometer metrics for monitoring
 * </ul>
 *
 * @see maple.expectation.infrastructure.mongodb.CharacterViewQueryService
 */
@Service
@ConditionalOnProperty(name = ["app.v5.enabled"], havingValue = "true", matchIfMissing = false)
class CharacterViewQueryServicePostgres(
    private val repository: CharacterValuationViewJpaRepository,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(CharacterViewQueryServicePostgres::class.java)

    /** Find character valuation view by user IGN (O(1) indexed lookup) */
    fun findByUserIgn(userIgn: String): CharacterValuationViewEntity? {
        val context = TaskContext.of("PostgresQuery", "FindByUserIgn", userIgn)

        return executor.executeOrDefault(
            {
                val result = repository.findByUserIgn(userIgn)
                if (result != null) {
                    meterRegistry
                        .timer("postgres.query.latency", "operation", "hit")
                        .record(Duration.ofMillis(1))
                    result
                } else {
                    meterRegistry
                        .timer("postgres.query.latency", "operation", "miss")
                        .record(Duration.ofMillis(1))
                    null
                }
            },
            null,
            context,
        )
    }

    /**
     * Upsert character valuation view
     *
     * @param entity The entity to upsert
     */
    @Transactional
    fun upsert(entity: CharacterValuationViewEntity) {
        val context = TaskContext.of("PostgresQuery", "Upsert", entity.userIgn)

        executor.executeVoid(
            {
                val existing = repository.findByUserIgn(entity.userIgn)

                if (existing != null) {
                    // Update existing
                    val incomingVersion = entity.version ?: 0L
                    val currentVersion = existing.version ?: 0L

                    if (incomingVersion > currentVersion) {
                        // Incoming update is newer - apply update
                        val updated = CharacterValuationViewEntity(
                            id = existing.id,
                            userIgn = entity.userIgn,
                            messageId = entity.messageId,
                            characterOcid = entity.characterOcid,
                            characterClass = entity.characterClass,
                            characterLevel = entity.characterLevel,
                            calculatedAt = entity.calculatedAt,
                            lastApiSyncAt = entity.lastApiSyncAt,
                            version = incomingVersion + 1,
                            lastAppliedVersion = incomingVersion,
                            totalExpectedCost = entity.totalExpectedCost,
                            maxPresetNo = entity.maxPresetNo,
                            presets = entity.presets,
                            fromCache = entity.fromCache,
                        )
                        repository.save(updated)
                        meterRegistry.counter("postgres.optimistic_lock.updated").increment()
                        log.debug(
                            "[OptimisticLock] Updated: userIgn={}, version={}->{}",
                            entity.userIgn,
                            currentVersion,
                            incomingVersion + 1,
                        )
                    } else {
                        // Incoming update is older or same - skip
                        meterRegistry.counter("postgres.optimistic_lock.skipped").increment()
                        log.debug(
                            "[OptimisticLock] Skipped: userIgn={}, incoming={}, current={}",
                            entity.userIgn,
                            incomingVersion,
                            currentVersion,
                        )
                    }
                } else {
                    // Insert new
                    val newEntity = CharacterValuationViewEntity(
                        id = null,
                        userIgn = entity.userIgn,
                        messageId = entity.messageId,
                        characterOcid = entity.characterOcid,
                        characterClass = entity.characterClass,
                        characterLevel = entity.characterLevel,
                        calculatedAt = entity.calculatedAt,
                        lastApiSyncAt = entity.lastApiSyncAt,
                        version = 1L,
                        lastAppliedVersion = entity.version ?: 1L,
                        totalExpectedCost = entity.totalExpectedCost,
                        maxPresetNo = entity.maxPresetNo,
                        presets = entity.presets,
                        fromCache = entity.fromCache,
                    )
                    repository.save(newEntity)
                    meterRegistry.counter("postgres.optimistic_lock.inserted").increment()
                    log.debug("[OptimisticLock] Inserted: userIgn={}, version=1", entity.userIgn)
                }
            },
            context,
        )
    }

    /** Delete by user IGN (for invalidation) */
    @Transactional
    fun deleteByUserIgn(userIgn: String) {
        val context = TaskContext.of("PostgresQuery", "Delete", userIgn)

        executor.executeVoid(
            { repository.deleteByUserIgn(userIgn) },
            context,
        )
    }

    /** Delete all documents (for testing) */
    @Transactional
    fun deleteAll() {
        val context = TaskContext.of("PostgresQuery", "DeleteAll", "all")

        executor.executeVoid(
            { repository.deleteAll() },
            context,
        )
    }

    /** Count all documents for a specific user IGN */
    fun countByUserIgn(userIgn: String): Long {
        val context = TaskContext.of("PostgresQuery", "Count", userIgn)

        return executor.executeOrDefault(
            { if (repository.findByUserIgn(userIgn) != null) 1L else 0L },
            0L,
            context,
        )
    }

    /**
     * Get the last applied event version for a user
     *
     * @param userIgn User in-game name
     * @return Last applied version, or 0L if no document exists
     */
    fun getLastAppliedVersion(userIgn: String): Long {
        val context = TaskContext.of("PostgresQuery", "GetLastAppliedVersion", userIgn)

        return executor.executeOrDefault(
            {
                val entity = repository.findByUserIgn(userIgn)
                entity?.lastAppliedVersion ?: 0L
            },
            0L,
            context,
        )
    }
}
