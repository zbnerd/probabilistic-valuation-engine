package maple.expectation.core.domain.model.like

/**
 * 좋아요 토글 + 카운트 통합 결과 (ADR-029)
 *
 * <p>단일 트랜잭션 내에서 토글 + 카운트 조회를 수행하여 일관성 보장.
 */
data class LikeToggleWithCount(
    val result: LikeToggleResult,
    val likeCount: Long,
)
