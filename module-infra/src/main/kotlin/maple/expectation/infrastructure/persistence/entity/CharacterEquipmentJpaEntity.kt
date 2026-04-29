package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import maple.expectation.util.converter.GzipStringConverter
import org.hibernate.annotations.Index

/**
 * JPA Entity for Character Equipment persistence.
 *
 * <p>This is a PERSISTENCE entity with JPA annotations. It belongs to the infrastructure layer and
 * should only be used by repository implementations.
 *
 * <p><b>Important:</b> Business logic has been moved to
 * [maple.expectation.domain.model.equipment.CharacterEquipment]. This entity is purely for database
 * mapping.
 *
 * @see maple.expectation.domain.model.equipment.CharacterEquipment
 */
@Entity
@Table(indexes = [Index(name = "idx_character_equipment_updated_at", columnList = "updated_at")])
open class CharacterEquipmentJpaEntity {

    @Id
    @Column(length = 100)
    open var ocid: String? = null

    @Convert(converter = GzipStringConverter::class)
    @Column(columnDefinition = "BYTEA", nullable = false)
    open var jsonContent: String? = null

    open var updatedAt: LocalDateTime? = null

    /**
     * Creates a new CharacterEquipmentJpaEntity.
     *
     * @param ocid the character OCID (primary key)
     * @param jsonContent the equipment JSON content (GZIP compressed)
     */
    constructor(ocid: String?, jsonContent: String?) {
        this.ocid = ocid
        this.jsonContent = jsonContent
        this.updatedAt = LocalDateTime.now()
    }

    /**
     * Default constructor for JPA.
     */
    protected constructor()

    /**
     * Creates a new CharacterEquipmentJpaEntity with explicit timestamp.
     *
     * <p>ADR-084: This factory method allows preserving the domain entity's updatedAt timestamp
     * when mapping to JPA, avoiding timestamp loss.
     *
     * @param ocid the character OCID (primary key)
     * @param jsonContent the equipment JSON content (GZIP compressed)
     * @param updatedAt the update timestamp (preserved from domain entity)
     */
    companion object {
        @JvmStatic
        fun of(
            ocid: String?,
            jsonContent: String?,
            updatedAt: LocalDateTime?,
        ): CharacterEquipmentJpaEntity {
            val entity = CharacterEquipmentJpaEntity()
            entity.ocid = ocid
            entity.jsonContent = jsonContent
            entity.updatedAt = updatedAt ?: LocalDateTime.now()
            return entity
        }
    }

    /**
     * Updates the equipment data and timestamp.
     *
     * <p><b>Note:</b> This method exists for JPA/Hibernate usage. Domain-level updates should use the
     * domain entity's
     * [maple.expectation.domain.model.equipment.CharacterEquipment.updateData]
     * method.
     *
     * @param newJsonContent the new JSON content
     */
    open fun updateData(newJsonContent: String?) {
        this.jsonContent = newJsonContent
        this.updatedAt = LocalDateTime.now()
    }
}
