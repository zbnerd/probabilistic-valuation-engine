package maple.expectation.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit
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
 * MongoDB CharacterViewQueryService의 PostgreSQL 마이그레이션 (Issue #590)
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Fast read from character_valuation_views table
 *   <li>LogicExecutor pattern for exception handling
 *   <li>Micrometer metrics for monitoring
 * </ul>
 */
@Service
@ConditionalOnProperty(name = ["v5.enabled"], havingValue = "true", matchIfMissing = false)
class CharacterViewQueryServicePostgres(
    private val repository: CharacterValuationViewJpaRepository,
    private val readModelWriteService: ExpectationReadModelWriteService,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(CharacterViewQueryServicePostgres::class.java)

    /** Find character valuation view by user IGN (O(1) indexed lookup) */
    fun findByUserIgn(userIgn: String): CharacterValuationViewEntity? {
        val context = TaskContext.of("PostgresQuery", "FindByUserIgn", userIgn)

        return executor.executeOrDefault(
            {
                val startNanos = System.nanoTime()
                val result = repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)
                meterRegistry
                    .timer("postgres.query.latency", "operation", if (result != null) "hit" else "miss")
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
                result
            },
            null,
            context,
        )
    }

    /**
     * Upsert character valuation view and propagate to read model.
     *
     * Transaction: Single @Transactional with REQUIRED propagation.
     * character_valuation_views is always written. character_expectation_read_model
     * is written on best-effort basis — failure is logged and does not roll back
     * the main entity save. Data self-heals on next calculation cycle.
     *
     * Caution: If called from @Async method, the transaction boundary
     * is the async method's caller. Use REQUIRES_NEW if isolation needed.
     *
     * @param entity The entity to upsert
     */
    @Transactional("transactionManager")
    fun upsert(entity: CharacterValuationViewEntity) {
        val context = TaskContext.of("PostgresQuery", "Upsert", entity.userIgn)
        executor.executeVoid({ performUpsert(entity) }, context)
    }

    private fun performUpsert(entity: CharacterValuationViewEntity) {
        val existing = findExistingEntity(entity)
        val saved = if (existing != null) {
            updateOrSkipExisting(existing, entity)
        } else {
            insertNew(entity)
        }
        // Always write to read model with latest available data
        val readModelSource = saved ?: existing
        if (readModelSource != null) {
            saveToReadModel(readModelSource)
        }
    }

    private fun updateOrSkipExisting(
        existing: CharacterValuationViewEntity,
        incoming: CharacterValuationViewEntity,
    ): CharacterValuationViewEntity? {
        val incomingVersion = incoming.version ?: 0L
        val currentVersion = existing.lastAppliedVersion ?: existing.version ?: 0L

        return if (incomingVersion > currentVersion) {
            repository.save(buildUpdatedEntity(existing, incoming, incomingVersion)).also {
                meterRegistry.counter("postgres.optimistic_lock.updated").increment()
                log.debug("[OptimisticLock] Updated: userIgn={}, version={}->{}", incoming.userIgn, currentVersion, incomingVersion)
            }
        } else {
            meterRegistry.counter("postgres.optimistic_lock.skipped").increment()
            log.debug("[Optgres] Skipping stale update: userIgn={}, version={}", existing.userIgn, incomingVersion)
            null
        }
    }

    private fun insertNew(entity: CharacterValuationViewEntity): CharacterValuationViewEntity = repository.save(buildNewEntity(entity)).also {
        meterRegistry.counter("postgres.optimistic_lock.inserted").increment()
        log.debug("[OptimisticLock] Inserted: userIgn={}, version=1", entity.userIgn)
    }

    private fun findExistingEntity(entity: CharacterValuationViewEntity): CharacterValuationViewEntity? = entity.messageId?.let(repository::findByMessageId)
        ?: repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(entity.userIgn)

    private fun buildUpdatedEntity(
        existing: CharacterValuationViewEntity,
        incoming: CharacterValuationViewEntity,
        incomingVersion: Long,
    ): CharacterValuationViewEntity = CharacterValuationViewEntity(
        id = existing.id,
        jpaVersion = existing.jpaVersion,
        userIgn = incoming.userIgn,
        messageId = incoming.messageId ?: existing.messageId,
        characterOcid = incoming.characterOcid ?: existing.characterOcid,
        characterClass = incoming.characterClass ?: existing.characterClass,
        characterLevel = incoming.characterLevel ?: existing.characterLevel,
        calculatedAt = incoming.calculatedAt ?: existing.calculatedAt,
        lastApiSyncAt = incoming.lastApiSyncAt ?: existing.lastApiSyncAt,
        version = maxOf(existing.version ?: 0L, incomingVersion) + 1,
        lastAppliedVersion = incomingVersion,
        totalExpectedCost = incoming.totalExpectedCost,
        maxPresetNo = incoming.maxPresetNo,
        presets = incoming.presets,
        fromCache = incoming.fromCache,
    )

    private fun buildNewEntity(entity: CharacterValuationViewEntity): CharacterValuationViewEntity = CharacterValuationViewEntity(
        id = null,
        jpaVersion = null,
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

    /** Delete by user IGN (for invalidation) */
    @Transactional("transactionManager")
    fun deleteByUserIgn(userIgn: String) {
        val context = TaskContext.of("PostgresQuery", "Delete", userIgn)

        executor.executeVoid(
            { repository.deleteByUserIgn(userIgn) },
            context,
        )
    }

    /** Delete all documents (for testing) */
    @Transactional("transactionManager")
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
            { if (repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn) != null) 1L else 0L },
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
                val entity = repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)
                entity?.lastAppliedVersion ?: 0L
            },
            0L,
            context,
        )
    }

    private fun saveToReadModel(entity: CharacterValuationViewEntity) {
        // calculated_at uses application time (Instant.now() at calculation).
        // TTL check in Next.js uses DB NOW(). MAX_STALE_SECONDS (5s) absorbs NTP drift.
        val calculatedAt = entity.calculatedAt
            ?: throw IllegalStateException("calculatedAt must be set before writing to read model: userIgn=${entity.userIgn}")
        val json = serializeEntityToJson(entity)
        executor.executeOrCatch(
            { readModelWriteService.writeToReadModelRaw(entity.userIgn, json, calculatedAt) },
            { e ->
                log.warn("[ReadModel] Non-fatal write failure (will retry on next calculation): userIgn={}", entity.userIgn, e)
            },
            TaskContext.of("ReadModel", "BestEffortWrite", entity.userIgn),
        )
    }

    private fun serializeEntityToJson(entity: CharacterValuationViewEntity): String = objectMapper.writeValueAsString(entity)
}
