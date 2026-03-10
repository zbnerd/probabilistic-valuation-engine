package maple.expectation.core.domain.model.character

/**
 * 유저 IGN (Value Object)
 *
 * <p>순수 도메인 모델 - JPA 의존 없음
 */
data class UserIgn(@get:JvmName("value") val value: String) {

    init {
        requireNotNull(value) { "UserIgn value cannot be null" }
        require(value.isNotBlank()) { "UserIgn value cannot be blank" }
    }

    companion object {
        @JvmStatic
        fun of(value: String): UserIgn = UserIgn(value)
    }
}
