package maple.expectation.infrastructure.aop.aspect

import maple.expectation.core.port.out.LikeBufferStrategy
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 좋아요 버퍼링 AOP (Issue #285: P1-13 구체 의존 제거)
 *
 * <p>LikeBufferStrategy 인터페이스에 의존하여 In-Memory/Redis 모드 모두 지원
 */
@Aspect
@Component
class BufferedLikeAspect(
    private val likeBufferStrategy: LikeBufferStrategy,
) {
    companion object {
        private val log = LoggerFactory.getLogger(BufferedLikeAspect::class.java)
    }

    @Around(
        "@annotation(maple.expectation.infrastructure.aop.annotation.BufferedLike) && args(userIgn, ..)",
    )
    fun doBuffer(joinPoint: ProceedingJoinPoint, userIgn: String): Any? {
        likeBufferStrategy.increment(userIgn, 1)
        log.debug("[AOP Buffering] Like request buffered: {}", userIgn)
        return null
    }
}
