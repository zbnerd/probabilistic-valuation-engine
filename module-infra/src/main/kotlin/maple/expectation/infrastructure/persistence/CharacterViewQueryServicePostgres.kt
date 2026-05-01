package maple.expectation.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.util.concurrent.TimeUnit
import maple.expectation.core.port.inbound.CharacterViewProjectionCommand
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity
import maple.expectation.infrastructure.persistence.repository.CharacterValuationViewJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(CharacterViewQueryServicePostgres::class.java)

    // ==================== CharacterViewQueryPort Implementation ====================

    @Transactional(value = "transactionManager", readOnly = true)
    fun findByUserIgn(userIgn: String): CharacterValuationViewEntity? = findByUserIgnEntity(userIgn)

    @Transactional(value = "transactionManager", readOnly = false)
    fun upsertFromCalculation(
        userIgn: String,
        messageId: String?,
        characterOcid: String?,
        characterClass: String?,
        characterLevel: Int?,
        totalExpectedCost: Long,
        maxPresetNo: Int,
        presetNo: Int,
        presetsJson: String,
    ) {
        val context = TaskContext.of("PostgresQuery", "UpsertFromCalculation", userIgn)
        executor.executeVoid({
            val presets: List<CharacterValuationViewEntity.PresetView>? = executor.executeOrDefault(
                {
                    objectMapper.readValue(
                        presetsJson,
                        objectMapper.typeFactory.constructCollectionType(List::class.java, CharacterValuationViewEntity.PresetView::class.java),
                    )
                },
                null,
                TaskContext.of("PostgresQuery", "ParsePresets", userIgn),
            )
            val entity = CharacterValuationViewEntity(
                userIgn = userIgn,
                messageId = messageId,
                characterOcid = characterOcid,
                characterClass = characterClass,
                characterLevel = characterLevel,
                totalExpectedCost = totalExpectedCost,
                maxPresetNo = maxPresetNo,
                presetNo = presetNo,
                presets = presets,
                calculatedAt = java.time.Instant.now(),
                fromCache = false,
                version = System.currentTimeMillis(),
            )
            upsert(entity)
        }, context)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun batchUpsertFromCalculations(commands: List<CharacterViewProjectionCommand>): Int {
        if (commands.isEmpty()) return 0
        val context = TaskContext.of("PostgresQuery", "BatchUpsertFromCalculation", commands.size.toString())
        return executor.executeOrDefault({ performBatchUpsert(commands) }, 0, context)
    }

    // ==================== Internal Methods ====================

    /** Find character valuation view by user IGN (O(1) indexed lookup) */
    private fun findByUserIgnEntity(userIgn: String): CharacterValuationViewEntity? {
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
    @Transactional(value = "transactionManager", readOnly = false)
    fun upsert(entity: CharacterValuationViewEntity) {
        val context = TaskContext.of("PostgresQuery", "Upsert", entity.userIgn)
        executor.executeVoid({ performUpsert(entity) }, context)
    }

    private fun performUpsert(entity: CharacterValuationViewEntity) {
        if (entity.messageId != null) {
            val presetsJson = entity.presets?.let { objectMapper.writeValueAsString(it) }
            upsertNative(entity, presetsJson)
            saveToReadModel(entity)
        } else {
            val existing = findExistingEntity(entity)
            val saved = if (existing != null) {
                updateOrSkipExisting(existing, entity)
            } else {
                insertNew(entity)
            }
            val readModelSource = saved ?: existing
            if (readModelSource != null) {
                saveToReadModel(readModelSource)
            }
        }
    }

    private fun upsertNative(entity: CharacterValuationViewEntity, presetsJson: String?) {
        val params = mapOf(
            "userIgn" to entity.userIgn,
            "messageId" to (entity.messageId ?: return),
            "characterOcid" to entity.characterOcid,
            "characterClass" to entity.characterClass,
            "characterLevel" to entity.characterLevel,
            "calculatedAt" to entity.calculatedAt?.let(Timestamp::from),
            "lastApiSyncAt" to entity.lastApiSyncAt?.let(Timestamp::from),
            "version" to (entity.version ?: 1L),
            "lastAppliedVersion" to (entity.lastAppliedVersion ?: entity.version ?: 1L),
            "totalExpectedCost" to entity.totalExpectedCost,
            "maxPresetNo" to entity.maxPresetNo,
            "presetNo" to entity.presetNo,
            "presets" to presetsJson,
            "fromCache" to entity.fromCache,
        )
        jdbc.update(
            """
            INSERT INTO character_valuation_views (
                user_ign, message_id, jpa_version, character_ocid, character_class, character_level,
                calculated_at, last_api_sync_at, version, last_applied_version,
                total_expected_cost, max_preset_no, preset_no, presets, from_cache
            ) VALUES (
                :userIgn, :messageId, 0, :characterOcid, :characterClass, :characterLevel,
                :calculatedAt, :lastApiSyncAt, :version + 1, :lastAppliedVersion,
                :totalExpectedCost, :maxPresetNo, :presetNo, CAST(:presets AS jsonb), :fromCache
            )
            ON CONFLICT (message_id) DO UPDATE SET
                user_ign = EXCLUDED.user_ign,
                jpa_version = character_valuation_views.jpa_version + 1,
                character_ocid = COALESCE(EXCLUDED.character_ocid, character_valuation_views.character_ocid),
                character_class = COALESCE(EXCLUDED.character_class, character_valuation_views.character_class),
                character_level = COALESCE(EXCLUDED.character_level, character_valuation_views.character_level),
                calculated_at = EXCLUDED.calculated_at,
                last_api_sync_at = COALESCE(EXCLUDED.last_api_sync_at, character_valuation_views.last_api_sync_at),
                version = character_valuation_views.version + 1,
                last_applied_version = EXCLUDED.last_applied_version,
                total_expected_cost = EXCLUDED.total_expected_cost,
                max_preset_no = EXCLUDED.max_preset_no,
                preset_no = EXCLUDED.preset_no,
                presets = EXCLUDED.presets,
                from_cache = EXCLUDED.from_cache
            WHERE character_valuation_views.last_applied_version < EXCLUDED.last_applied_version
            """,
            params,
        )
    }

    private fun performBatchUpsert(commands: List<CharacterViewProjectionCommand>): Int {
        val now = java.time.Instant.now()
        val versionBase = System.currentTimeMillis()
        val rows = commands.mapIndexed { index, command ->
            val version = versionBase + index
            val presets = parsePresets(command)
            val entity = CharacterValuationViewEntity(
                userIgn = command.userIgn,
                messageId = command.messageId,
                characterOcid = command.characterOcid,
                characterClass = command.characterClass,
                characterLevel = command.characterLevel,
                totalExpectedCost = command.totalExpectedCost,
                maxPresetNo = command.maxPresetNo,
                presetNo = command.presetNo,
                presets = presets,
                calculatedAt = now,
                fromCache = false,
                version = version,
                lastAppliedVersion = version,
            )
            entity to MapSqlParameterSource()
                .addValue("userIgn", entity.userIgn)
                .addValue("messageId", entity.messageId)
                .addValue("characterOcid", entity.characterOcid)
                .addValue("characterClass", entity.characterClass)
                .addValue("characterLevel", entity.characterLevel)
                .addValue("calculatedAt", entity.calculatedAt?.let(Timestamp::from))
                .addValue("lastApiSyncAt", entity.lastApiSyncAt?.let(Timestamp::from))
                .addValue("version", entity.version ?: 1L)
                .addValue("lastAppliedVersion", entity.lastAppliedVersion ?: entity.version ?: 1L)
                .addValue("totalExpectedCost", entity.totalExpectedCost)
                .addValue("maxPresetNo", entity.maxPresetNo)
                .addValue("presetNo", entity.presetNo)
                .addValue("presets", command.presetsJson)
                .addValue("fromCache", entity.fromCache)
        }

        val counts = jdbc.batchUpdate(
            """
            INSERT INTO character_valuation_views (
                user_ign, message_id, jpa_version, character_ocid, character_class, character_level,
                calculated_at, last_api_sync_at, version, last_applied_version,
                total_expected_cost, max_preset_no, preset_no, presets, from_cache
            ) VALUES (
                :userIgn, :messageId, 0, :characterOcid, :characterClass, :characterLevel,
                :calculatedAt, :lastApiSyncAt, :version + 1, :lastAppliedVersion,
                :totalExpectedCost, :maxPresetNo, :presetNo, CAST(:presets AS jsonb), :fromCache
            )
            ON CONFLICT (message_id) DO UPDATE SET
                user_ign = EXCLUDED.user_ign,
                jpa_version = character_valuation_views.jpa_version + 1,
                character_ocid = COALESCE(EXCLUDED.character_ocid, character_valuation_views.character_ocid),
                character_class = COALESCE(EXCLUDED.character_class, character_valuation_views.character_class),
                character_level = COALESCE(EXCLUDED.character_level, character_valuation_views.character_level),
                calculated_at = EXCLUDED.calculated_at,
                last_api_sync_at = COALESCE(EXCLUDED.last_api_sync_at, character_valuation_views.last_api_sync_at),
                version = character_valuation_views.version + 1,
                last_applied_version = EXCLUDED.last_applied_version,
                total_expected_cost = EXCLUDED.total_expected_cost,
                max_preset_no = EXCLUDED.max_preset_no,
                preset_no = EXCLUDED.preset_no,
                presets = EXCLUDED.presets,
                from_cache = EXCLUDED.from_cache
            WHERE character_valuation_views.last_applied_version < EXCLUDED.last_applied_version
            """,
            rows.map { it.second }.toTypedArray(),
        )
        saveToReadModelBatch(rows.map { it.first })
        return counts.sumOf { if (it > 0) it else 0 }
    }

    private fun parsePresets(command: CharacterViewProjectionCommand): List<CharacterValuationViewEntity.PresetView>? = executor.executeOrDefault(
        {
            objectMapper.readValue(
                command.presetsJson,
                objectMapper.typeFactory.constructCollectionType(List::class.java, CharacterValuationViewEntity.PresetView::class.java),
            )
        },
        null,
        TaskContext.of("PostgresQuery", "ParsePresets", command.userIgn),
    )

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
        presetNo = incoming.presetNo,
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
        presetNo = entity.presetNo,
        presets = entity.presets,
        fromCache = entity.fromCache,
    )

    /** Delete all documents (for testing) */
    @Transactional(value = "transactionManager", readOnly = false)
    fun deleteAll() {
        val context = TaskContext.of("PostgresQuery", "DeleteAll", "all")

        executor.executeVoid(
            { repository.deleteAll() },
            context,
        )
    }

    /** Count all documents for a specific user IGN */
    @Transactional(value = "transactionManager", readOnly = true)
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
    @Transactional(value = "transactionManager", readOnly = true)
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

    private fun saveToReadModelBatch(entities: List<CharacterValuationViewEntity>) {
        val commands = entities.map { entity ->
            val calculatedAt = entity.calculatedAt
                ?: throw IllegalStateException("calculatedAt must be set before writing to read model: userIgn=${entity.userIgn}")
            ReadModelWriteCommand(
                userIgn = entity.userIgn,
                json = serializeEntityToJson(entity),
                calculatedAt = calculatedAt,
            )
        }
        executor.executeOrCatch(
            { readModelWriteService.writeToReadModelRawBatch(commands) },
            { e ->
                log.warn("[ReadModel] Non-fatal batch write failure (will retry on next calculation): rows={}", entities.size, e)
                0
            },
            TaskContext.of("ReadModel", "BestEffortBatchWrite", entities.size.toString()),
        )
    }

    /**
     * Serialize entity to JSON for read model storage.
     *
     * TODO(#727): Currently serializes the JPA entity which diverges from the V4/V5 API DTO
     * contract (e.g., totalExpectedCost is Long in entity vs Double in DTO, presets use
     * PresetView vs PresetExpectation shape). The entity uses @JsonIgnore on internal fields
     * (id, jpaVersion, version) so those are excluded, but the remaining field types and
     * structures may not match the API response shape that the Next.js query server expects.
     * Proper fix requires introducing a port/interface in module-core for DTO-accurate
     * serialization, allowing module-web to provide the correct mapping implementation.
     */
    private fun serializeEntityToJson(entity: CharacterValuationViewEntity): String = objectMapper.writeValueAsString(entity)
}
