package maple.expectation.application.dto

import java.time.LocalDateTime
import maple.expectation.domain.model.equipment.CharacterEquipment

/**
 * CharacterEquipment Data Transfer Object
 */
data class CharacterEquipmentDto(
    var ocid: String? = null,
    var jsonContent: String? = null
) : BaseDto() {

    companion object {
        @JvmStatic
        fun from(entity: CharacterEquipment): CharacterEquipmentDto {
            return CharacterEquipmentDto(
                ocid = entity.ocid(),
                jsonContent = entity.jsonContent()
            ).apply {
                updatedAt = entity.updatedAt
            }
        }

        @JvmStatic
        fun forCreation(ocid: String, jsonContent: String): CharacterEquipmentDto {
            return CharacterEquipmentDto(
                ocid = ocid,
                jsonContent = jsonContent
            ).apply {
                initTimestamps()
            }
        }
    }

    fun toEntity(): CharacterEquipment {
        return CharacterEquipment.of(
            ocid ?: throw IllegalStateException("ocid must not be null"),
            jsonContent ?: throw IllegalStateException("jsonContent must not be null")
        )
    }

    fun hasData(): Boolean {
        val content = jsonContent ?: return false
        return content.isNotBlank()
    }

    fun isFresh(ttlMinutes: Long): Boolean {
        val updated = updatedAt ?: return false
        val threshold = LocalDateTime.now().minusMinutes(ttlMinutes)
        return updated.isAfter(threshold)
    }

    fun isExpired(ttlMinutes: Long): Boolean {
        return !isFresh(ttlMinutes)
    }

    fun getAgeInMinutes(): Long? {
        val updated = updatedAt ?: return null
        return java.time.Duration.between(updated, LocalDateTime.now()).toMinutes()
    }
}
