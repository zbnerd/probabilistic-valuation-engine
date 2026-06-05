package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.CharacterValuationEntity
import maple.expectation.infrastructure.persistence.jpa.CharacterValuationJpaRepository
import org.springframework.lang.Nullable
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Character Valuation JPA Repository Implementation (PostgreSQL JSONB)
 *
 * <p><strong>Purpose:</strong> Replaces MongoDB CharacterValuationRepository with PostgreSQL
 * JSONB implementation for V5 CQRS read model.
 *
 * <h3>Transaction Management</h3>
 *
 * <p>Uses explicit `"transactionManager"` qualifier to prevent ambiguity in multi-datasource
 * environments (MySQL command model + PostgreSQL query model).
 *
 * <h3>JSONB Handling</h3>
 *
 * <ul>
 *   <li>presets stored as JSONB column (GIN indexed for fast queries)
 *   <li>Optimized for read-heavy CQRS pattern
 *   <li>Optimistic locking via JPA @Version
 * </ul>
 *
 * @see <a href="../../../../../../docs/01_ADR/ADR-036-v5-cqrs-mongodb.md">ADR-036: V5 CQRS MongoDB → PostgreSQL JSONB Migration</a>
 * @see <a href="../../../../../../docs/01_ADR/ADR-013-multi-datasource-transaction-strategy.md">ADR-013: Multi-DataSource Transaction Strategy</a>
 */
@Repository
@Transactional(value = "transactionManager", readOnly = true)
open class CharacterValuationRepositoryImpl(
    private val jpaRepo: CharacterValuationJpaRepository,
) {

    /**
     * Find character valuation view by user in-game name.
     *
     * <p>O(1) indexed lookup via `idx_valuation_user_ign`.
     *
     * @param userIgn User in-game name
     * @return JPA entity or null
     */
    @Nullable
    open fun findByUserIgn(userIgn: String?): CharacterValuationEntity? = jpaRepo.findByUserIgn(userIgn)
        .orElseGet { null }

    /**
     * Find character valuation view by message ID.
     *
     * <p>Used for idempotency checks in Discord bot responses.
     *
     * @param messageId Discord message ID
     * @return JPA entity or null
     */
    @Nullable
    open fun findByMessageId(messageId: String?): CharacterValuationEntity? = jpaRepo.findByMessageId(messageId)
        .orElseGet { null }

    /**
     * Find all valuation views for a user, ordered by calculation time (newest first).
     *
     * @param userIgn User in-game name
     * @return list of JPA entities
     */
    open fun findAllByUserIgnOrderByCalculatedAtDesc(userIgn: String?): List<CharacterValuationEntity> = jpaRepo.findAllByUserIgnOrderByCalculatedAtDesc(userIgn)

    /**
     * Save or update character valuation view.
     *
     * <p>JPA optimistic locking via @Version prevents lost updates.
     *
     * @param entity JPA entity to save
     * @return saved entity with generated ID
     */
    @Transactional(value = "transactionManager", readOnly = false)
    open fun save(entity: CharacterValuationEntity): CharacterValuationEntity {
        requireNotNull(entity) { "Entity cannot be null" }
        return jpaRepo.save(entity)
    }

    /**
     * Delete character valuation view by user in-game name.
     *
     * <p>Used for cache invalidation when character data changes.
     *
     * @param userIgn User in-game name
     */
    @Transactional(value = "transactionManager", readOnly = false)
    open fun deleteByUserIgn(userIgn: String?) {
        jpaRepo.deleteByUserIgn(userIgn)
    }

    /**
     * Delete all valuation views.
     *
     * <p>Use with caution - primarily for testing.
     */
    @Transactional(value = "transactionManager", readOnly = false)
    open fun deleteAll() {
        jpaRepo.deleteAll()
    }

    /**
     * Count valuation views by user in-game name.
     *
     * @param userIgn User in-game name
     * @return count (0 or 1 due to unique constraint)
     */
    open fun countByUserIgn(userIgn: String?): Long = jpaRepo.countByUserIgn(userIgn)

    /**
     * Check if valuation view exists by message ID.
     *
     * @param messageId Discord message ID
     * @return true if exists
     */
    open fun existsByMessageId(messageId: String?): Boolean = jpaRepo.existsByMessageId(messageId)

    /**
     * Find by ID.
     *
     * @param id Entity ID
     * @return JPA entity or null
     */
    @Nullable
    open fun findById(id: Long?): CharacterValuationEntity? = if (id != null) {
        jpaRepo.findById(id).orElseGet { null }
    } else {
        null
    }

    /**
     * Find or create entity by user IGN.
     *
     * <p>Useful for upsert patterns where you want to ensure an entity exists.
     *
     * @param userIgn User in-game name
     * @return existing or new entity
     */
    open fun findOrCreateByUserIgn(userIgn: String): CharacterValuationEntity = findByUserIgn(userIgn) ?: CharacterValuationEntity(
        userIgn = userIgn,
        calculatedAt = java.time.Instant.now(),
        version = 0L,
    )

    /**
     * Upsert with version-based conflict resolution.
     *
     * <p>Mimics MongoDB optimistic locking behavior:
     * <ul>
     *   <li>If incoming version > current version: update
     *   <li>If incoming version <= current version: skip (realtime wins)
     * </ul>
     *
     * @param entity Entity to upsert
     * @return true if updated, false if skipped
     */
    @Transactional(value = "transactionManager", readOnly = false)
    open fun upsertByVersion(entity: CharacterValuationEntity): Boolean {
        val existing = findByUserIgn(entity.userIgn)

        return if (existing != null) {
            val incomingVersion = entity.version ?: 0L
            val currentVersion = existing.version ?: 0L

            if (incomingVersion > currentVersion) {
                // Update existing entity
                existing.messageId = entity.messageId
                existing.characterOcid = entity.characterOcid
                existing.characterClass = entity.characterClass
                existing.characterLevel = entity.characterLevel
                existing.calculatedAt = entity.calculatedAt
                existing.lastApiSyncAt = entity.lastApiSyncAt
                existing.version = incomingVersion + 1
                existing.lastAppliedVersion = entity.lastAppliedVersion
                existing.totalExpectedCost = entity.totalExpectedCost
                existing.maxPresetNo = entity.maxPresetNo
                existing.presets = entity.presets
                existing.fromCache = entity.fromCache
                jpaRepo.save(existing)
                true
            } else {
                // Skip update (realtime data is newer)
                false
            }
        } else {
            // Insert new entity
            entity.version = (entity.version ?: 0L) + 1
            entity.lastAppliedVersion = entity.version
            jpaRepo.save(entity)
            true
        }
    }
}
