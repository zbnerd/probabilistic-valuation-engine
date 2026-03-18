package maple.expectation.testfixtures

import java.util.UUID
import maple.expectation.core.domain.model.character.CharacterId
import maple.expectation.core.domain.model.character.GameCharacter
import maple.expectation.core.domain.model.character.UserIgn
import maple.expectation.domain.v2.Member

/**
 * Test fixtures for domain entities
 *
 * Usage:
 * ```kotlin
 * val character = GameCharacterFixture.create()
 * val member = MemberFixture.createGuest()
 * ```
 */
object GameCharacterFixture {
    fun create(
        userIgn: String = "TestCharacter_${System.currentTimeMillis()}",
        ocid: String = "test-ocid-${UUID.randomUUID()}",
    ): GameCharacter = GameCharacter.create(
        userIgn = UserIgn(userIgn),
        characterId = CharacterId(ocid),
    )

    fun createWithLikeCount(
        userIgn: String = "TestCharacter_${System.currentTimeMillis()}",
        ocid: String = "test-ocid-${UUID.randomUUID()}",
        likeCount: Long = 0L,
    ): GameCharacter = GameCharacter.create(
        userIgn = UserIgn(userIgn),
        characterId = CharacterId(ocid),
    )
}

object MemberFixture {
    fun createGuest(
        uuid: String = UUID.randomUUID().toString(),
        initialPoint: Long = 1000L,
    ): Member {
        // Use reflection to access private constructor
        val constructor = Member::class.java.getDeclaredConstructor(String::class.java, Long::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(uuid, initialPoint)
    }
}
