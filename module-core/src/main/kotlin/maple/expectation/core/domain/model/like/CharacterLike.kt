package maple.expectation.core.domain.model.like

import java.time.LocalDateTime

/**
 * 캐릭터 좋아요 도메인 모델
 *
 * <p>순수 도메인 - JPA 의존 없음
 *
 * <h3>SOLID 준수</h3>
 *
 * <ul>
 *   <li>SRP: 좋아요 관계 표현만 담당
 *   <li>OCP: 불변 data class로 안전한 상태 보장
 * </ul>
 */
data class CharacterLike(
    @get:JvmName("id") val id: Long?,
    @get:JvmName("targetOcid") val targetOcid: String,
    @get:JvmName("likerAccountId") val likerAccountId: String,
    @get:JvmName("createdAt") val createdAt: LocalDateTime
) {

  /** 새 좋아요 생성 */
  companion object {
    @JvmStatic
    fun create(targetOcid: String, likerAccountId: String): CharacterLike {
      requireNotNull(targetOcid) { "targetOcid cannot be null" }
      requireNotNull(likerAccountId) { "likerAccountId cannot be null" }
      return CharacterLike(null, targetOcid, likerAccountId, LocalDateTime.now())
    }

    /**
     * 영속 레이어 복원 전용
     *
     * <p>JPA/Redis에서 전체 필드 복원 시 사용
     */
    @JvmStatic
    fun restore(
      id: Long?,
      targetOcid: String,
      likerAccountId: String,
      createdAt: LocalDateTime
    ): CharacterLike {
      return CharacterLike(id, targetOcid, likerAccountId, createdAt)
    }

    /**
     * Factory method for creating CharacterLike from existing data
     *
     * <p>Used by DTOs to convert back to domain model
     *
     * @param id the like ID
     * @param targetOcid target character OCID
     * @param likerAccountId the account ID of the user who liked
     * @param createdAt creation timestamp
     * @return CharacterLike instance
     */
    @JvmStatic
    fun of(
      id: Long?,
      targetOcid: String,
      likerAccountId: String,
      createdAt: LocalDateTime
    ): CharacterLike {
      return CharacterLike(id, targetOcid, likerAccountId, createdAt)
    }

    /**
     * Factory method for creating new CharacterLike (without ID)
     *
     * <p>Used by DTOs to convert new likes
     *
     * @param targetOcid target character OCID
     * @param likerAccountId the account ID of the user who liked
     * @return CharacterLike instance with current timestamp
     */
    @JvmStatic
    fun of(targetOcid: String, likerAccountId: String): CharacterLike {
      return CharacterLike(null, targetOcid, likerAccountId, LocalDateTime.now())
    }
  }

  /** ID가 할당된 새 인스턴스 반환 (영속화 후) */
  fun withId(id: Long): CharacterLike = copy(id = id)

  /** 자기 좋아요 여부 확인 */
  fun isSelfLike(): Boolean = targetOcid != null && targetOcid == likerAccountId

  /** 새 좋아요 여부 (ID 없음) */
  fun isNew(): Boolean = id == null
}
