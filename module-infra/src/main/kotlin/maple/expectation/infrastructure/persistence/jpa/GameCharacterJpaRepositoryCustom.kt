package maple.expectation.infrastructure.persistence.jpa

import maple.expectation.infrastructure.persistence.entity.GameCharacterJpaEntity

/**
 * Custom JPA Repository methods for GameCharacter.
 *
 * <p>This interface provides complex queries that don't fit standard Spring Data JPA naming
 * conventions.
 */
interface GameCharacterJpaRepositoryCustom {

    /**
     * Find all active characters (updated within last 30 days).
     *
     * @return list of active JPA entities
     */
    fun findActiveCharacters(): List<GameCharacterJpaEntity>
}
