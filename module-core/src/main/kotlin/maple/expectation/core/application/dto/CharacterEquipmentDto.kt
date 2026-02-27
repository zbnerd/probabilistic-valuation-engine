package maple.expectation.core.application.dto

import maple.expectation.domain.model.equipment.CharacterEquipment
import java.time.Duration
import java.time.LocalDateTime

/**
 * CharacterEquipment Data Transfer Object
 */
data class CharacterEquipmentDto(
    val ocid: String,
    val jsonContent: String,
    override var updatedAt: LocalDateTime?
) : BaseDto() {

    companion object {
        @JvmStatic
        fun from(entity: CharacterEquipment): CharacterEquipmentDto {
            return CharacterEquipmentDto(
                ocid = entity.ocid() ?: "",
                jsonContent = entity.jsonContent() ?: "",
                updatedAt = entity.updatedAt
            )
        }

        @JvmStatic
        fun forCreation(ocid: String, jsonContent: String): CharacterEquipmentDto {
            val dto = CharacterEquipmentDto(
                ocid = ocid,
                jsonContent = jsonContent,
                updatedAt = LocalDateTime.now()
            )
            dto.initTimestamps()
            return dto
        }
    }

    fun toEntity(): CharacterEquipment {
        return CharacterEquipment.of(this.ocid, this.jsonContent)
    }

    fun hasData(): Boolean {
        return this.jsonContent.isNotBlank()
    }

    fun isFresh(ttlMinutes: Long): Boolean {
        val updatedAt = this.updatedAt ?: return false
        val threshold = LocalDateTime.now().minusMinutes(ttlMinutes)
        return updatedAt.isAfter(threshold)
    }

    fun isExpired(ttlMinutes: Long): Boolean {
        return !isFresh(ttlMinutes)
    }

    fun getAgeInMinutes(): Long? {
        val updatedAt = this.updatedAt ?: return null
        return Duration.between(updatedAt, LocalDateTime.now()).toMinutes()
    }
}
