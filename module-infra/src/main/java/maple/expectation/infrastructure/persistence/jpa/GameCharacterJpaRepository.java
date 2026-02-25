package maple.expectation.infrastructure.persistence.jpa;

import maple.expectation.infrastructure.persistence.entity.GameCharacterJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA Repository for GameCharacter.
 *
 * <p>This is an INTERNAL repository interface used only by infrastructure layer. Domain layer uses
 * {@link maple.expectation.domain.repository.GameCharacterRepository} instead.
 *
 * @see maple.expectation.infrastructure.persistence.repository.GameCharacterRepositoryImpl
 */
public interface GameCharacterJpaRepository extends JpaRepository<GameCharacterJpaEntity, Long> {

  /**
   * Find character by OCID.
   *
   * @param ocid character OCID
   * @return JPA entity or empty
   */
  java.util.Optional<GameCharacterJpaEntity> findByOcid(String ocid);

  /**
   * Find character by user IGN.
   *
   * @param userIgn in-game name
   * @return JPA entity or empty
   */
  java.util.Optional<GameCharacterJpaEntity> findByUserIgn(String userIgn);

  /**
   * Check if character exists by OCID.
   *
   * @param ocid character OCID
   * @return true if exists
   */
  boolean existsByOcid(String ocid);

  /**
   * Delete character by OCID.
   *
   * @param ocid character OCID
   */
  void deleteByOcid(String ocid);

  /**
   * Find active characters (updated within last 30 days).
   *
   * <p>This query uses a native query for efficiency.
   *
   * @return list of active JPA entities
   */
  @Query(
      """
      SELECT gc FROM GameCharacterJpaEntity gc
      WHERE gc.updatedAt > :threshold
      ORDER BY gc.updatedAt DESC
      """)
  java.util.List<GameCharacterJpaEntity> findActiveCharacters(java.time.LocalDateTime threshold);

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
  @Query(
      "UPDATE GameCharacterJpaEntity g SET g.likeCount = g.likeCount + :count WHERE g.userIgn = :userIgn")
  void incrementLikeCount(String userIgn, long count);
}
