package maple.expectation.infrastructure.persistence.jpa

import maple.expectation.infrastructure.persistence.entity.CharacterLikeJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional
import java.util.Optional

/**
 * Spring Data JPA Repository for CharacterLike.
 *
 *
 * This is an INTERNAL repository interface used only by infrastructure layer. Domain layer uses
 * [maple.expectation.domain.repository.CharacterLikeRepository] instead.
 *
 * @see maple.expectation.infrastructure.persistence.repository.CharacterLikeRepositoryImpl
 */
interface CharacterLikeJpaRepository : JpaRepository<CharacterLikeJpaEntity, Long> {

  /**
   * Find like by target OCID and liker account ID.
   *
   * @param targetOcid OCID of character
   * @param likerAccountId account ID of user
   * @return JPA entity or empty
   */
  fun findByTargetOcidAndLikerAccountId(
      targetOcid: String?,
      likerAccountId: String?
  ): Optional<CharacterLikeJpaEntity>

  /**
   * Find all likes by liker account ID, ordered by creation time (newest first).
   *
   * @param likerAccountId account ID of user
   * @return list of JPA entities
   */
  fun findByLikerAccountIdOrderByCreatedAtDesc(likerAccountId: String?): List<CharacterLikeJpaEntity>

  /**
   * Find all likes for target OCID, ordered by creation time (newest first).
   *
   * @param targetOcid OCID of character
   * @return list of JPA entities
   */
  fun findByTargetOcidOrderByCreatedAtDesc(targetOcid: String?): List<CharacterLikeJpaEntity>

  /**
   * Count likes by target OCID.
   *
   * @param targetOcid OCID of character
   * @return count of likes
   */
  fun countByTargetOcid(targetOcid: String?): Long

  /**
   * Count likes by liker account ID.
   *
   * @param likerAccountId account ID of user
   * @return count of likes
   */
  fun countByLikerAccountId(likerAccountId: String?): Long

  /**
   * Check if like exists by target OCID and liker account ID.
   *
   * @param targetOcid OCID of character
   * @param likerAccountId account ID of user
   * @return true if exists
   */
  fun existsByTargetOcidAndLikerAccountId(targetOcid: String?, likerAccountId: String?): Boolean

  /**
   * Delete like by target OCID and liker account ID.
   *
   * @param targetOcid OCID of character
   * @param likerAccountId account ID of user
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  fun deleteByTargetOcidAndLikerAccountId(targetOcid: String?, likerAccountId: String?)
}
