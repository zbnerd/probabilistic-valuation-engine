package maple.expectation.infrastructure.persistence.jpa

import java.util.Optional
import maple.expectation.infrastructure.persistence.entity.CharacterValuationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional

/**
 * Spring Data JPA Repository for CharacterValuation JSONB Read Model.
 *
 * <p><strong>Purpose:</strong> Internal JPA repository used only by infrastructure layer.
 * Replaces MongoDB CharacterValuationRepository with PostgreSQL JSONB implementation.
 *
 * <h3>Transaction Management</h3>
 *
 * <p>Uses explicit `"transactionManager"` qualifier to prevent ambiguity in multi-datasource
 * environments (MySQL + PostgreSQL).
 *
 * @see <a href="../../../../../../docs/01_ADR/ADR-013-multi-datasource-transaction-strategy.md">ADR-013: Multi-DataSource Transaction Strategy</a>
 * @see maple.expectation.infrastructure.persistence.repository.CharacterValuationRepositoryImpl
 */
interface CharacterValuationJpaRepository : JpaRepository<CharacterValuationEntity, Long> {

    /**
     * Find character valuation view by user in-game name.
     *
     * <p>O(1) indexed lookup via `idx_valuation_user_ign`.
     *
     * @param userIgn User in-game name
     * @return JPA entity or empty
     */
    fun findByUserIgn(userIgn: String?): Optional<CharacterValuationEntity>

    /**
     * Find character valuation view by message ID.
     *
     * <p>Unique index ensures at most one result.
     *
     * @param messageId Discord message ID for idempotency
     * @return JPA entity or empty
     */
    fun findByMessageId(messageId: String?): Optional<CharacterValuationEntity>

    /**
     * Delete character valuation view by user in-game name.
     *
     * <p>Used for cache invalidation when character data changes.
     *
     * @param userIgn User in-game name
     */
    @Modifying(clearAutomatically = true)
    @Transactional("transactionManager")
    fun deleteByUserIgn(userIgn: String?)

    /**
     * Count valuation views by user in-game name.
     *
     * @param userIgn User in-game name
     * @return count (0 or 1 due to unique constraint on userIgn)
     */
    fun countByUserIgn(userIgn: String?): Long

    /**
     * Check if valuation view exists by message ID.
     *
     * @param messageId Discord message ID
     * @return true if exists
     */
    fun existsByMessageId(messageId: String?): Boolean

    /**
     * Find all valuation views for a user, ordered by calculation time (newest first).
     *
     * @param userIgn User in-game name
     * @return list of JPA entities
     */
    fun findAllByUserIgnOrderByCalculatedAtDesc(userIgn: String?): List<CharacterValuationEntity>
}
