package maple.expectation.infrastructure.aop.aspect

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import maple.expectation.error.exception.ObservabilityException
import maple.expectation.infrastructure.aop.annotation.ObservedTransaction
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory

@Aspect
@Component
class ObservabilityAspect(
    private val meterRegistry: MeterRegistry,
    private val executor: LogicExecutor
) {
    companion object {
        private val log = LoggerFactory.getLogger(ObservabilityAspect::class.java)
    }

    @Around("@annotation(observedTransaction)")
    fun trackMetrics(joinPoint: ProceedingJoinPoint, observedTransaction: ObservedTransaction): Any? {
        val metricName = observedTransaction.value
        val sample = Timer.start(meterRegistry)

        // ✅ TaskContext 적용: Component="Observability", Operation="Track"
        return executor.executeOrCatch(
            { executeAndRecordSuccess(joinPoint, metricName, sample) },
            { ex -> recordFailureAndThrow(metricName, joinPoint, sample, ex) },
            TaskContext.of("Observability", "Track", metricName)
        )
    }

    /**
     * 성공 시 메트릭 기록
     *
     * <p>Issue #138 FIX: 고카디널리티 태그 제거
     *
     * <ul>
     *   <li>제거: class, method 태그 (메트릭 폭발 방지)</li>
     *   <li>유지: result 태그 (success/failure - 저카디널리티)</li>
     * </ul>
     */
    @Throws(Throwable::class)
    private fun executeAndRecordSuccess(joinPoint: ProceedingJoinPoint, metricName: String, sample: Timer.Sample): Any {
        val result = joinPoint.proceed()

        // Issue #138: class, method 태그 제거 (고카디널리티 방지)
        sample.stop(
            Timer.builder(metricName)
                .tag("result", "success")
                .register(meterRegistry)
        )

        return result
    }

    /**
     * 실패 시 메트릭 기록 및 예외 재전파
     *
     * <p>Issue #138 FIX: 고카디널리티 태그 제거
     *
     * <ul>
     *   <li>제거: exception 태그 (메트릭 폭발 방지)</li>
     *   <li>로그에는 여전히 상세 정보 기록 (디버깅용)</li>
     * </ul>
     */
    private fun recordFailureAndThrow(
        metricName: String,
        joinPoint: ProceedingJoinPoint,
        sample: Timer.Sample,
        e: Throwable
    ): Nothing {
        // 로그에는 상세 정보 유지 (디버깅용)
        log.error(
            "[Metric-Failure] ID: {}, Method: {}, Error: {}",
            metricName,
            joinPoint.signature.name,
            e.message
        )

        // Issue #138: exception 태그 제거 (고카디널리티 방지)
        sample.stop(
            Timer.builder(metricName)
                .tag("result", "failure")
                .register(meterRegistry)
        )

        // Issue #138: failure 카운터도 exception 태그 제거
        meterRegistry.counter("$metricName.failure").increment()

        throw if (e is RuntimeException) e else ObservabilityException("Observability tracking failed", e)
    }
}
