package maple.expectation.infrastructure.aop.aspect

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * ★ 중요: 포인트컷 경로가 변경되었습니다. maple.expectation.aop.SimpleLogTime ->
 * maple.expectation.aop.annotation.SimpleLogTime
 */
@Aspect
@Component
class SimpleLogAspect {
    companion object {
        private val log = LoggerFactory.getLogger(SimpleLogAspect::class.java)
    }

    @Around("@annotation(maple.expectation.aop.annotation.SimpleLogTime)")
    fun logExecutionTime(joinPoint: ProceedingJoinPoint): Any? {
        val start = System.currentTimeMillis()

        val proceed = joinPoint.proceed()

        val duration = System.currentTimeMillis() - start
        val methodName = joinPoint.signature.name

        log.info("⏱️ [Performance] 메서드: {} | 소요 시간: {}ms", methodName, duration)

        return proceed
    }
}
