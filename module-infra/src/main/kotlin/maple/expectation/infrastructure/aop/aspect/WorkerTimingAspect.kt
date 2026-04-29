package maple.expectation.infrastructure.aop.aspect

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import maple.expectation.infrastructure.aop.annotation.TimedStage
import maple.expectation.infrastructure.aop.annotation.TimedTask
import maple.expectation.infrastructure.aop.context.WorkerMdcKeys
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.util.StopWatch

/**
 * 워커 태스크/스테이지 시간 측정 AOP
 *
 * @TimedTask, @TimedStage 어노테이션이 적용된 메서드의 실행 시간을 측정하여
 * Micrometer Timer에 기록하고 structured log를 출력합니다.
 *
 * ## 설계 결정
 * - LogicExecutor 사용 안함 (순환 참조 방지, TraceAspect 선례)
 * - try-finally 직접 사용 (AOP infrastructure 계층은 예외)
 * - 모든 상태는 지역 변수 (thread-safe)
 * - MDC 값이 없으면 "unknown"으로 graceful degradation
 *
 * ## Metric 이름 (low-cardinality)
 * - `expectation.worker.task.duration{queue, priority, result}`
 * - `expectation.worker.stage.duration{stage, result}`
 *
 * ## Prometheus/Grafana 쿼리 예시
 * - p50: `histogram_quantile(0.5, rate(expectation_worker_task_duration_seconds_bucket[5m]))`
 * - p95: `histogram_quantile(0.95, rate(expectation_worker_stage_duration_seconds_bucket{stage="calculate"}[5m]))`
 * - p99: `histogram_quantile(0.99, rate(expectation_worker_task_duration_seconds_bucket[5m]))`
 */
@Aspect
@Component
class WorkerTimingAspect(
    private val meterRegistry: MeterRegistry,
) {

    companion object {
        private val log = LoggerFactory.getLogger(WorkerTimingAspect::class.java)
        private const val TASK_METRIC = "expectation.worker.task.duration"
        private const val STAGE_METRIC = "expectation.worker.stage.duration"
        private const val FALLBACK = "unknown"
    }

    @Around("@annotation(timedTask)")
    fun aroundTimedTask(joinPoint: ProceedingJoinPoint, timedTask: TimedTask): Any? {
        val sample = Timer.start(meterRegistry)
        val sw = StopWatch()
        sw.start()

        var success = true
        var error: Throwable? = null

        // [AOP Intrinsic Exception] try-catch-finally required here because:
        // 1. AOP aspects are infrastructure that wraps LogicExecutor calls — injecting LogicExecutor would cause circular dependency
        // 2. Timing requires recording success/failure AND rethrowing — LogicExecutor methods either swallow or translate exceptions
        // 3. All state is local (no shared mutable state), so this is safe
        try {
            return joinPoint.proceed()
        } catch (t: Throwable) {
            success = false
            error = t
            throw t
        } finally {
            sw.stop()
            recordTaskTiming(sample, sw.totalTimeMillis, timedTask.value, success, error)
        }
    }

    @Around("@annotation(timedStage)")
    fun aroundTimedStage(joinPoint: ProceedingJoinPoint, timedStage: TimedStage): Any? {
        val stageName = timedStage.value
        val thresholdMs = timedStage.warnThresholdMs

        val sample = Timer.start(meterRegistry)
        val sw = StopWatch()
        sw.start()

        var success = true
        var error: Throwable? = null

        // [AOP Intrinsic Exception] Same rationale as aroundTimedTask — see comment above
        try {
            return joinPoint.proceed()
        } catch (t: Throwable) {
            success = false
            error = t
            throw t
        } finally {
            sw.stop()
            recordStageTiming(sample, sw.totalTimeMillis, stageName, thresholdMs, success, error)
        }
    }

    private fun recordTaskTiming(
        sample: Timer.Sample,
        durationMs: Long,
        taskType: String,
        success: Boolean,
        error: Throwable?,
    ) {
        val resultTag = resultOf(success)
        val queue = WorkerMdcKeys.getQueueName() ?: FALLBACK
        val priority = WorkerMdcKeys.getPriority() ?: FALLBACK
        val taskId = WorkerMdcKeys.getTaskId() ?: FALLBACK

        sample.stop(
            Timer.builder(TASK_METRIC)
                .tag("queue", queue)
                .tag("priority", priority)
                .tag("result", resultTag)
                .register(meterRegistry),
        )

        if (success) {
            log.info(
                "[TaskTimer] event=task.complete type={} durationMs={} queue={} priority={} result={} taskId={}",
                taskType,
                durationMs,
                queue,
                priority,
                resultTag,
                taskId,
            )
        } else {
            log.error(
                "[TaskTimer] event=task.failure type={} durationMs={} queue={} priority={} result={} taskId={} error={}",
                taskType,
                durationMs,
                queue,
                priority,
                resultTag,
                taskId,
                error?.message,
            )
        }
    }

    private fun recordStageTiming(
        sample: Timer.Sample,
        durationMs: Long,
        stageName: String,
        thresholdMs: Long,
        success: Boolean,
        error: Throwable?,
    ) {
        val resultTag = resultOf(success)
        val taskId = WorkerMdcKeys.getTaskId() ?: FALLBACK
        val traceId = MDC.get("requestId") ?: FALLBACK

        sample.stop(
            Timer.builder(STAGE_METRIC)
                .tag("stage", stageName)
                .tag("result", resultTag)
                .register(meterRegistry),
        )

        if (thresholdMs > 0 && durationMs > thresholdMs) {
            log.warn(
                "[StageTimer] event=stage.slow stage={} durationMs={} thresholdMs={} taskId={} traceId={}",
                stageName,
                durationMs,
                thresholdMs,
                taskId,
                traceId,
            )
        }

        if (success) {
            log.info(
                "[StageTimer] event=stage.complete stage={} durationMs={} result={} taskId={} traceId={}",
                stageName,
                durationMs,
                resultTag,
                taskId,
                traceId,
            )
        } else {
            log.error(
                "[StageTimer] event=stage.failure stage={} durationMs={} result={} taskId={} traceId={} error={}",
                stageName,
                durationMs,
                resultTag,
                taskId,
                traceId,
                error?.message,
            )
        }
    }

    private fun resultOf(success: Boolean): String = if (success) "success" else "failure"
}
