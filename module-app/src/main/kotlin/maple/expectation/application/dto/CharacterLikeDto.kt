package maple.expectation.application.dto

import java.time.LocalDateTime
import maple.expectation.domain.model.like.CharacterLike

/**
 * CharacterLike Data Transfer Object
 */
data class CharacterLikeDto(
    var targetOcid: String? = null,
    var likerAccountId: String? = null
) : BaseDto() {

    var id: Long? = null

    companion object {
        @JvmStatic
        fun from(entity: CharacterLike): CharacterLikeDto {
            return CharacterLikeDto(
                targetOcid = entity.targetOcid,
                likerAccountId = entity.likerAccountId
            ).apply {
                id = entity.id
                }
        }

        @JvmStatic
        fun forCreation(targetOcid: String, likerAccountId: String): CharacterLikeDto {
            return CharacterLikeDto(
                targetOcid = targetOcid,
                likerAccountId = likerAccountId
            ).apply {
                initTimestamps()
            }
        }
    }

    fun toEntity(): CharacterLike {
        return CharacterLike(
            id,
            targetOcid ?: throw IllegalStateException("targetOcid must not be null"),
            likerAccountId ?: throw IllegalStateException("likerAccountId must not be null"),
            createdAt ?: LocalDateTime.now()
        )
    }

    fun isSelfLike(userCharacterOcids: List<String>?): Boolean {
        return userCharacterOcids != null && userCharacterOcids.contains(targetOcid)
    }

    fun getAgeInDays(): Long? {
        val created = createdAt ?: return null
        return java.time.Duration.between(created, LocalDateTime.now()).toDays()
    }

    fun isRecent(days: Long): Boolean {
        val created = createdAt ?: return false
        val threshold = LocalDateTime.now().minusDays(days)
        return created.isAfter(threshold)
    }
}
