package maple.expectation.core.port.inbound

import maple.expectation.core.domain.model.like.LikeToggleResult

/**
 * 좋아요 토글 Port (ADR-005, ADR-029)
 *
 * <p>책임: 좋아요 토글, 상태 조회
 *
 * <p>구현체: LikeToggleService (module-app)
 */
interface LikeTogglePort {

    /**
     * 좋아요 토글
     *
     * @param targetUserIgn 대상 캐릭터 닉네임
     * @param likerAccountId 요청자 계정 ID
     * @param myOcids 요청자 소유 캐릭터 OCID 집합
     * @return LIKED 또는 UNLIKED
     */
    fun toggleLike(targetUserIgn: String, likerAccountId: String, myOcids: Set<String>): LikeToggleResult

    /**
     * 좋아요 상태 + 카운트 통합 조회
     *
     * @param targetUserIgn 대상 캐릭터 닉네임
     * @param likerAccountId 요청자 계정 ID
     * @return 좋아요 여부
     */
    fun isLiked(targetUserIgn: String, likerAccountId: String): Boolean

    /**
     * 좋아요 수 조회
     *
     * @param targetUserIgn 대상 캐릭터 닉네임
     * @return 좋아요 수
     */
    fun getLikeCount(targetUserIgn: String): Long
}
