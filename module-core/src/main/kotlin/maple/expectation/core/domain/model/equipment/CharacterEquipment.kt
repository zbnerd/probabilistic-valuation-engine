package maple.expectation.core.domain.model.equipment

import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import maple.expectation.core.domain.model.character.CharacterId

/**
 * 캐릭터 장비 도메인 모델
 *
 * <p>순수 도메인 - JPA 의존 없음
 *
 * <h3>SOLID 준수</h3>
 *
 * <ul>
 *   <li>SRP: 장비 데이터 표현 및 관련 비즈니스 규칙만 담당
 *   <li>OCP: 불변 data class로 안전한 상태 보장
 * </ul>
 */
data class CharacterEquipment(
    @get:JvmName("characterId") val characterId: CharacterId,
    @get:JvmName("equipmentData") val equipmentData: EquipmentData,
    @get:JvmName("updatedAt") val updatedAt: LocalDateTime,
) {

    /** 새 장비 생성 */
    companion object {
        @JvmStatic
        fun create(characterId: CharacterId, equipmentData: EquipmentData): CharacterEquipment = CharacterEquipment(characterId, equipmentData, LocalDateTime.now())

        /** 빈 장비 생성 (기본값) */
        @JvmStatic
        fun createEmpty(characterId: CharacterId): CharacterEquipment = CharacterEquipment(characterId, EquipmentData.empty(), LocalDateTime.now())

        /**
         * 영속 레이어 복원 전용
         *
         * <p>JPA/Redis에서 전체 필드 복원 시 사용
         */
        @JvmStatic
        fun restore(
            characterId: CharacterId,
            equipmentData: EquipmentData,
            updatedAt: LocalDateTime,
        ): CharacterEquipment = CharacterEquipment(characterId, equipmentData, updatedAt)

        /** OCID로 새 장비 생성 (편의 메서드) */
        @JvmStatic
        fun of(ocid: String, json: String): CharacterEquipment = CharacterEquipment(
            CharacterId.of(ocid),
            EquipmentData.of(json),
            LocalDateTime.now(),
        )
    }

    /** 장비 데이터 업데이트된 새 인스턴스 반환 */
    fun withUpdatedData(newData: String): CharacterEquipment = copy(equipmentData = EquipmentData.of(newData), updatedAt = LocalDateTime.now())

    /** 장비 데이터 업데이트된 새 인스턴스 반환 */
    fun withUpdatedData(newData: EquipmentData): CharacterEquipment = copy(equipmentData = newData, updatedAt = LocalDateTime.now())

    /** 캐릭터 ID 변경된 새 인스턴스 반환 */
    fun withCharacterId(newCharacterId: CharacterId): CharacterEquipment = copy(characterId = newCharacterId)

    /** 캐릭터 OCID 반환 */
    fun ocid(): String? = characterId.value

    /** 장비 데이터 JSON 컨텐츠 반환 */
    fun jsonContent(): String? = equipmentData.jsonContent()

    /**
     * 데이터 신선성 확인 - updatedAt이 TTL 내에 있는지 확인
     *
     * @param ttl 캐시 유효 기간
     * @return true if updatedAt is within TTL from now
     */
    fun isFresh(ttl: Duration): Boolean = updatedAt != null &&
        ChronoUnit.MILLIS.between(updatedAt, LocalDateTime.now()) < ttl.toMillis()

    /** 데이터 존재 여부 확인 */
    fun hasData(): Boolean = equipmentData.isNotEmpty()

    /** 데이터 신선성 만료 여부 */
    fun isStale(ttl: Duration): Boolean = !isFresh(ttl)

    /** 빈 장비 여부 */
    fun isEmpty(): Boolean = !hasData()
}
