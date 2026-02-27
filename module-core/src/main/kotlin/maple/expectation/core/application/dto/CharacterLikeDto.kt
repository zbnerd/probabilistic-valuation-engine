package maple.expectation.core.application.dto

import maple.expectation.domain.model.like.CharacterLike
import java.time.Duration
import java.time.LocalDateTime

/**
 * CharacterLike Data Transfer Object
 */
data class CharacterLikeDto(
    val id: Long? = null,
    val targetOcid: String,
    val likerAccountId: String,
    override var createdAt: LocalDateTime?
) : BaseDto() {

    companion object {
        @JvmStatic
        fun from(entity: CharacterLike): CharacterLikeDto {
            return CharacterLikeDto(
                id = entity.id,
                targetOcid = entity.targetOcid,
                likerAccountId = entity.likerAccountId,
                createdAt = entity.createdAt
            )
        }

        @JvmStatic
        fun forCreation(targetOcid: String, likerAccountId: String): CharacterLikeDto {
            return CharacterLikeDto(
                targetOcid = targetOcid,
                likerAccountId = likerAccountId,
                createdAt = LocalDateTime.now()
            )
        }
    }

    fun toEntity(): CharacterLike {
        return CharacterLike.of(this.targetOcid, this.likerAccountId)
    }

    fun isSelfLike(userCharacterOcids: List<String>?): Boolean {
        return userCharacterOcids != null && this.targetOcid in userCharacterOcids
    }

    fun getAgeInDays(): Long? {
        val createdAt = this.createdAt ?: return null
        return Duration.between(createdAt, LocalDateTime.now()).toDays()
    }

    fun isRecent(days: Long): Boolean {
        val createdAt = this.createdAt ?: return false
        val threshold = LocalDateTime.now().minusDays(days)
        return createdAt.isAfter(threshold)
    }
}
