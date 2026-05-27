package maple.restcontroller.controller.v6

import maple.expectation.core.domain.model.security.AuthenticatedUser
import maple.expectation.core.port.inbound.LikeTogglePort
import maple.restcontroller.auth.JwtAuthInterceptor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CompletableFuture

@RestController
@RequestMapping("/api/v6/characters")
@ConditionalOnProperty(name = ["expectation.v6.enabled"], havingValue = "true")
class LikeController(
    private val likeTogglePort: LikeTogglePort,
) {

    @PostMapping("/{userIgn}/like")
    fun toggleLike(
        @PathVariable userIgn: String,
        @RequestAttribute(JwtAuthInterceptor.USER_ATTRIBUTE) user: AuthenticatedUser,
    ): CompletableFuture<ResponseEntity<LikeToggleResponse>> = CompletableFuture.supplyAsync {
        val result = likeTogglePort.toggleLikeWithCount(userIgn, user.accountId, user.myOcids)
        ResponseEntity.ok(
            LikeToggleResponse(
                targetUserIgn = userIgn,
                liked = result.result == maple.expectation.core.domain.model.like.LikeToggleResult.LIKED,
                likeCount = result.likeCount,
            )
        )
    }

    @GetMapping("/{userIgn}/like/status")
    fun getLikeStatus(
        @PathVariable userIgn: String,
        @RequestAttribute(JwtAuthInterceptor.USER_ATTRIBUTE) user: AuthenticatedUser,
    ): CompletableFuture<ResponseEntity<LikeStatusResponse>> = CompletableFuture.supplyAsync {
        val liked = likeTogglePort.isLiked(userIgn, user.accountId)
        val count = likeTogglePort.getLikeCount(userIgn)
        ResponseEntity.ok(LikeStatusResponse(targetUserIgn = userIgn, liked = liked, likeCount = count))
    }
}

data class LikeToggleResponse(
    val targetUserIgn: String,
    val liked: Boolean,
    val likeCount: Long,
)

data class LikeStatusResponse(
    val targetUserIgn: String,
    val liked: Boolean,
    val likeCount: Long,
)
