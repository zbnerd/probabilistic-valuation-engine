package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Rejection Policy Factory - Thread Pool Rejection Policy 생성 전담 클래스
 *
 * ## 책임
 *
 * - Alert Executor용 LOGGING_ABORT_POLICY 생성 (샘플링 로그 포함)
 * - Expectation Executor용 EXPECTATION_ABORT_POLICY 생성 (Issue #168 수정사항 반영)
 * - Log Storm 방지를 위한 샘플링 카운터 관리
 * - Micrometer rejected Counter 등록
 *
 * ## Issue #168 수정사항
 *
 * - CallerRunsPolicy → AbortPolicy (톰캣 스레드 고갈 방지)
 * - 503 응답 + Retry-After 헤더 반환
 * - rejected Counter 추가 (ExecutorServiceMetrics 미제공)
 */
@Component
class RejectionPolicyFactory(
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(RejectionPolicyFactory::class.java)

    companion object {
        /** 로그 샘플링 간격: 1초에 1회만 WARN 로그 (log storm 방지) */
        private val REJECT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1)

        /**
         * Alert Executor용 로그 샘플링 카운터 (#271 V5 P1 검토 완료)
         *
         * ## Stateless 검토 결과
         *
         * 이 카운터들은 **로그 샘플링 용도**로, 정확한 클러스터 집계가 필요 없습니다:
         *
         * - 목적: log storm 방지 (1초에 1회만 WARN)
         * - 영향: 인스턴스별 독립 샘플링 → 정상 동작
         * - 결론: Micrometer Counter로 교체 불필요 (이미 rejected Counter 별도 존재)
         *
         * 실제 rejected 메트릭은 `executor.rejected` Counter로 Micrometer에 집계됩니다.
         */
        private val lastRejectLogNanos = AtomicLong(0)
        private val rejectedSinceLastLog = AtomicLong(0)

        /** Expectation Executor용 샘플링 카운터 (AlertExecutor와 분리) */
        private val expectationLastRejectNanos = AtomicLong(0)
        private val expectationRejectedSinceLastLog = AtomicLong(0)
    }

    /**
     * Best-effort + Future 완료 보장 정책 (Alert Executor용)
     *
     * ## 핵심 계약
     *
     * - **드롭 허용**: 알림은 best-effort이므로 큐 포화 시 드롭 가능
     * - **Future 완료 보장**: CompletableFuture.runAsync가 "영원히 pending" 되는 것을 방지하기 위해
     *     RejectedExecutionException을 throw하여 Future가 exceptionally 완료되도록 함
     * - **Log storm 방지**: 샘플링으로 1초에 1회만 WARN 로그
     * - **Shutdown 시나리오**: 종료 중 거절은 정상이므로 로그 레벨 낮춤
     *
     * ## DiscardPolicy와의 차이
     *
     * DiscardPolicy는 조용히 드롭하여 runAsync Future가 영원히 pending됨 → 메모리 누수/관측성 누락
     *
     * 이 핸들러는 throw하여 Future가 완료되고, exceptionally()에서 처리 가능
     *
     * @return RejectedExecutionHandler 인스턴스
     */
    fun createAlertAbortPolicy(): RejectedExecutionHandler {
        // Context7 Best Practice: rejected Counter 등록 (ExecutorServiceMetrics 미제공)
        val alertRejectedCounter = Counter.builder("executor.rejected")
            .tag("name", "alert")
            .description("Number of tasks rejected due to queue full")
            .register(meterRegistry)

        return RejectedExecutionHandler { r, executor ->
            alertRejectedCounter.increment()

            // 종료 중 거절은 정상 시나리오이므로 로그 없이 즉시 throw
            if (executor.isShutdown || executor.isTerminating) {
                throw RejectedExecutionException("AlertExecutor rejected (shutdown in progress)")
            }

            // 샘플링: 1초에 1회만 WARN 로그 (log storm 방지)
            val dropped = rejectedSinceLastLog.incrementAndGet()
            val now = System.nanoTime()
            val prev = lastRejectLogNanos.get()

            if (now - prev >= REJECT_LOG_INTERVAL_NANOS && lastRejectLogNanos.compareAndSet(prev, now)) {
                val count = rejectedSinceLastLog.getAndSet(0)
                log.warn(
                    "[AlertExecutor] Task rejected (queue full). droppedInLastWindow={}, taskClass={}, poolSize={}, activeCount={}, queueSize={}",
                    count,
                    r.javaClass.name,
                    executor.poolSize,
                    executor.activeCount,
                    executor.queue.size,
                )
            }

            // ★ 핵심: throw하여 runAsync Future가 exceptionally 완료되도록 함
            throw RejectedExecutionException("AlertExecutor queue full (dropped=$dropped)")
        }
    }

    /**
     * Expectation 계산 전용 AbortPolicy (Issue #168)
     *
     * ## CallerRunsPolicy 제거 이유
     *
     * - **톰캣 스레드 고갈**: 큐 포화 시 톰캣 스레드에서 작업 실행 → 전체 API 마비
     * - **메트릭 불가**: rejected count = 0으로 보임 (서킷브레이커 동작 불가)
     * - **SLA 위반**: 요청 처리 시간 비정상 증가
     *
     * ## AbortPolicy 적용 효과
     *
     * - **즉시 거부**: O(1) 시간 복잡도, 톰캣 스레드 보호
     * - **503 응답**: GlobalExceptionHandler에서 Retry-After 헤더와 함께 반환
     * - **메트릭 가시성**: Micrometer executor.rejected Counter로 모니터링
     *
     * ## ⚠️ Write-Behind 패턴 주의
     *
     * 이 정책은 **읽기 전용 작업에만** 적용하세요. DB 저장 등 쓰기 작업에 적용하면 데이터 유실 위험!
     *
     * @return RejectedExecutionHandler 인스턴스
     */
    fun createExpectationAbortPolicy(): RejectedExecutionHandler {
        // Context7 Best Practice: rejected Counter 등록 (ExecutorServiceMetrics 미제공)
        val expectationRejectedCounter = Counter.builder("executor.rejected")
            .tag("name", "expectation.compute")
            .description("Number of tasks rejected due to queue full")
            .register(meterRegistry)

        return RejectedExecutionHandler { r, executor ->
            expectationRejectedCounter.increment()

            // 종료 중 거절은 정상 시나리오
            if (executor.isShutdown || executor.isTerminating) {
                throw RejectedExecutionException("ExpectationExecutor rejected (shutdown in progress)")
            }

            // 샘플링: 1초에 1회만 WARN 로그 (log storm 방지)
            val dropped = expectationRejectedSinceLastLog.incrementAndGet()
            val now = System.nanoTime()
            val prev = expectationLastRejectNanos.get()

            if (now - prev >= REJECT_LOG_INTERVAL_NANOS &&
                expectationLastRejectNanos.compareAndSet(prev, now)
            ) {
                val count = expectationRejectedSinceLastLog.getAndSet(0)
                log.warn(
                    "[ExpectationExecutor] Task rejected (queue full). " +
                        "droppedInLastWindow={}, poolSize={}, activeCount={}, queueSize={}",
                    count,
                    executor.poolSize,
                    executor.activeCount,
                    executor.queue.size,
                )
            }

            // Future 완료 보장을 위해 예외 throw
            throw RejectedExecutionException("ExpectationExecutor queue full (capacity exceeded)")
        }
    }
}
