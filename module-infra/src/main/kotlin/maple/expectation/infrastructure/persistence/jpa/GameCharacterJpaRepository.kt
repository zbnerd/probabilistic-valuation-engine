package maple.expectation.infrastructure.persistence.jpa

import java.time.LocalDateTime
import maple.expectation.infrastructure.persistence.entity.GameCharacterJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Spring Data JPA Repository for GameCharacter.
 *
 * <p>This is an INTERNAL repository interface used only by infrastructure layer. Domain layer uses
 * [maple.expectation.domain.repository.GameCharacterRepository] instead.
 *
 * @see maple.expectation.infrastructure.persistence.repository.GameCharacterRepositoryImpl
 */
interface GameCharacterJpaRepository : JpaRepository<GameCharacterJpaEntity, Long> {

    /**
     * Find character by OCID.
     *
     * @param ocid character OCID
     * @return JPA entity or empty
     */
    fun findByOcid(ocid: String?): GameCharacterJpaEntity?

    /**
     * Find character by user IGN.
     *
     * @param userIgn in-game name
     * @return JPA entity or empty
     */
    fun findByUserIgn(userIgn: String?): GameCharacterJpaEntity?

    /**
     * Check if character exists by OCID.
     *
     * @param ocid character OCID
     * @return true if exists
     */
    fun existsByOcid(ocid: String?): Boolean

    /**
     * Delete character by OCID.
     *
     * @param ocid character OCID
     */
    fun deleteByOcid(ocid: String?)

    /**
     * Find active characters (updated within last 30 days).
     *
     * <p>This query uses a JPQL query for efficiency.
     *
     * @return list of active JPA entities
     */
    @Query(
        """
        SELECT gc FROM GameCharacterJpaEntity gc
        WHERE gc.updatedAt > :threshold
        ORDER BY gc.updatedAt DESC
        """,
    )
    fun findActiveCharacters(threshold: LocalDateTime?): List<GameCharacterJpaEntity>

    /**
     * Increment the like count for a character by user IGN.
     *
     * <p>This method is used for batch updates in the like synchronization process. It directly
     * updates the like_count in the database without loading the entity.
     *
     * @param userIgn the in-game name of the character
     * @param count the amount to increment (can be positive or negative)
     */
    @Modifying
    @Query("UPDATE GameCharacterJpaEntity g SET g.likeCount = GREATEST(COALESCE(g.likeCount, 0) + :count, 0) WHERE g.userIgn = :userIgn")
    fun incrementLikeCount(userIgn: String, count: Long)

    /**
     * Find all characters by user IGNs (batch query).
     *
     * @param userIgns list of in-game names
     * @return list of matching JPA entities
     */
    fun findAllByUserIgnIn(userIgns: List<String>): List<GameCharacterJpaEntity>

    /**
     * Find all characters by fingerprint for self-like prevention (#662).
     *
     * @param fingerprint API Key HMAC-SHA256 hash
     * @return list of matching entities
     */
    fun findAllByFingerprint(fingerprint: String): List<GameCharacterJpaEntity>

    /**
     * Lazy backfill: stamp fingerprint on NULL rows only (#662).
     * Idempotent — only updates if fingerprint is currently NULL.
     *
     * @return number of rows updated (0 if already stamped)
     */
    @Modifying
    @Query(
        "UPDATE GameCharacterJpaEntity g SET g.fingerprint = :fingerprint, g.accountId = :accountId " +
            "WHERE g.ocid = :ocid AND g.fingerprint IS NULL",
    )
    fun updateFingerprintByOcid(
        @Param("ocid") ocid: String,
        @Param("fingerprint") fingerprint: String,
        @Param("accountId") accountId: String,
    ): Int
}
