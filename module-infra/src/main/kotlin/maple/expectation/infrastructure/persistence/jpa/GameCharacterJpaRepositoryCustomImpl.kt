package maple.expectation.infrastructure.persistence.jpa

import jakarta.persistence.EntityManager
import maple.expectation.infrastructure.persistence.entity.GameCharacterJpaEntity
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/** Implementation of custom JPA repository methods for GameCharacter. */
@Component
open class GameCharacterJpaRepositoryCustomImpl(
    private val entityManager: EntityManager
) : GameCharacterJpaRepositoryCustom {

    override fun findActiveCharacters(): List<GameCharacterJpaEntity> {
        val threshold = LocalDateTime.now().minusDays(30)
        return entityManager
            .createQuery(
                """
                SELECT gc FROM GameCharacterJpaEntity gc
                WHERE gc.updatedAt > :threshold
                ORDER BY gc.updatedAt DESC
                """,
                GameCharacterJpaEntity::class.java
            )
            .setParameter("threshold", threshold)
            .resultList
    }
}
