package maple.expectation.core.domain.model.character

/**
 * 캐릭터 식별자 (Value Object)
 *
 * <p>순수 도메인 모델 - JPA 의존 없음
 */
data class CharacterId(@get:JvmName("value") val value: String) {

    init {
        requireNotNull(value) { "CharacterId value cannot be null" }
        require(value.isNotBlank()) { "CharacterId value cannot be blank" }
    }

    companion object {
        @JvmStatic
        fun of(value: String): CharacterId = CharacterId(value)
    }
}
