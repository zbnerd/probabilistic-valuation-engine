package maple.expectation.web.dto.v4

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 좋아요 토글 응답 DTO
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LikeToggleResponse(
    val targetUserIgn: String,
    val liked: Boolean,
    val likeCount: Long? = null,
)

/**
 * 좋아요 상태 조회 응답 DTO
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LikeStatusResponse(
    val targetUserIgn: String,
    val liked: Boolean,
    val likeCount: Long,
)
