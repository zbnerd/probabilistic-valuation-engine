package maple.expectation.core.domain.model.like

/**
 * 좋아요 토글 결과 (ADR-029)
 *
 * <p>Liked: 좋아요 추가됨, Unliked: 좋아요 취소됨
 */
enum class LikeToggleResult {
    LIKED,
    UNLIKED,
}
