package maple.expectation.core.domain.model.character

import maple.expectation.core.domain.model.equipment.CharacterEquipment
import java.time.LocalDateTime

/**
 * 게임 캐릭터 도메인 모델 (순수 도메인)
 *
 * <p>JPA 엔티티는 module-infra에 별도로 존재
 *
 * <p>이 클래스는 비즈니스 로직에서 사용하는 순수 도메인 모델
 *
 * <h3>SOLID 준수</h3>
 *
 * <ul>
 *   <li>SRP: 캐릭터 데이터 표현 및 관련 비즈니스 규칙만 담당
 *   <li>OCP: 불변 필드 + with* 메서드로 안전한 상태 변화
 * </ul>
 */
data class GameCharacter(
    @get:JvmName("getId") val id: Long?,
    @get:JvmName("getUserIgn") val userIgn: UserIgn,
    @get:JvmName("getCharacterId") val characterId: CharacterId,
    @get:JvmName("getEquipment") val equipment: CharacterEquipment?,
    @get:JvmName("getWorldName") val worldName: String?,
    @get:JvmName("getCharacterClass") val characterClass: String?,
    @get:JvmName("getCharacterImage") val characterImage: String?,
    @get:JvmName("getBasicInfoUpdatedAt") val basicInfoUpdatedAt: LocalDateTime?,
    @get:JvmName("getLikeCount") val likeCount: Long,
    @get:JvmName("getVersion") val version: Long?,
    @get:JvmName("getUpdatedAt") val updatedAt: LocalDateTime
) {

  /** 새 캐릭터 생성 (최소 필드만) */
  companion object {
    @JvmStatic
    fun create(userIgn: UserIgn, characterId: CharacterId): GameCharacter {
      return GameCharacter(
        id = null,
        userIgn = userIgn,
        characterId = characterId,
        equipment = null,
        worldName = null,
        characterClass = null,
        characterImage = null,
        basicInfoUpdatedAt = null,
        likeCount = 0L,
        version = null,
        updatedAt = LocalDateTime.now()
      )
    }

    /**
     * JPA/Redis 복원을 위한 정적 팩토리
     *
     * <p>Persist 레이어에서 전체 필드를 복원할 때 사용
     */
    @JvmStatic
    fun restore(
      id: Long?,
      characterId: CharacterId,
      userIgn: UserIgn,
      equipment: CharacterEquipment?,
      worldName: String?,
      characterClass: String?,
      characterImage: String?,
      basicInfoUpdatedAt: LocalDateTime?,
      likeCount: Long,
      version: Long?,
      updatedAt: LocalDateTime
    ): GameCharacter {
      return GameCharacter(
        id = id,
        userIgn = userIgn,
        characterId = characterId,
        equipment = equipment,
        worldName = worldName,
        characterClass = characterClass,
        characterImage = characterImage,
        basicInfoUpdatedAt = basicInfoUpdatedAt,
        likeCount = likeCount,
        version = version,
        updatedAt = updatedAt
      )
    }
  }

  /** 장비 정보 포함된 새 인스턴스 반환 */
  fun withEquipment(equipment: CharacterEquipment): GameCharacter {
    return copy(
      equipment = equipment,
      updatedAt = LocalDateTime.now()
    )
  }

  /** 기본 정보 업데이트된 새 인스턴스 반환 */
  fun withBasicInfo(worldName: String, characterClass: String, characterImage: String): GameCharacter {
    return copy(
      worldName = worldName,
      characterClass = characterClass,
      characterImage = characterImage,
      basicInfoUpdatedAt = LocalDateTime.now(),
      updatedAt = LocalDateTime.now()
    )
  }

  /** 좋아요 수 증가된 새 인스턴스 반환 */
  fun withIncrementedLike(): GameCharacter {
    return copy(
      likeCount = likeCount + 1,
      updatedAt = LocalDateTime.now()
    )
  }

  /** 버전 증가된 새 인스턴스 반환 (낙관적 락) */
  fun withNextVersion(): GameCharacter {
    return copy(
      version = if (version != null) version + 1 else 1L,
      updatedAt = LocalDateTime.now()
    )
  }

  /** ID가 할당된 새 인스턴스 반환 (영속化 후) */
  fun withId(id: Long): GameCharacter {
    return copy(id = id)
  }

  /** 장비 데이터 존재 여부 */
  fun hasEquipment(): Boolean = equipment != null && equipment.hasData()

  /** 기본 정보 존재 여부 */
  fun hasBasicInfo(): Boolean = !worldName.isNullOrBlank()

  /** 새 캐릭터 여부 (ID 없음) */
  fun isNew(): Boolean = id == null

  /** OCID 반환 (편의 메서드) */
  fun getOcid(): String? = characterId?.value
}
