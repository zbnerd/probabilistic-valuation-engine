package maple.expectation.infrastructure.persistence

import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional

/**
 * Spring Data JPA Repository for CharacterEquipmentJpaEntity.
 *
 * <p>This repository is used by the adapter layer to persist JPA entities.
 */
@Repository
interface CharacterEquipmentJpaRepository : JpaRepository<CharacterEquipmentJpaEntity, String> {

    /**
     * Find equipment updated after threshold (for TTL-based caching).
     *
     * @param ocid the character OCID
     * @param threshold the minimum update time
     * @return equipment if found and fresh
     */
    fun findByOcidAndUpdatedAtAfter(ocid: String, threshold: LocalDateTime): Optional<CharacterEquipmentJpaEntity>
}
