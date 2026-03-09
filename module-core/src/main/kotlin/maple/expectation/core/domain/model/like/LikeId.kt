package maple.expectation.core.domain.model.like

/**
 * 좋아요 ID (Value Object)
 *
 * <p>순수 도메인 모델 - JPA 의존 없음
 */
data class LikeId(val value: Long) {

    init {
        require(value >= 0) { "LikeId value cannot be negative" }
    }
}
