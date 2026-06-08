package maple.expectation.infrastructure.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ForkJoinPool
import org.springframework.stereotype.Component

/**
 * Issue #1198: ADR-723 §4 Result/Evidence 의 cross-module saturation detection 을 위해
 * `ForkJoinPool.commonPool()` 의 CPU activity 를 Prometheus 로 노출.
 *
 * <h3>Trigger (per ADR-723 §4)</h3>
 * <blockquote>
 * `activeThreadCount > coreCount * 2` 지속 시 dedicated executor 분리 검토.
 * </blockquote>
 *
 * <h3>노출 메트릭 (3)</h3>
 * <ul>
 *   <li>{@code forkjoinpool.active.threads} - 현재 활성 스레드 수 (core count 기반 saturation 판단용)</li>
 *   <li>{@code forkjoinpool.queued.tasks} - 큐에 대기 중인 작업 수 (backpressure 판단용)</li>
 *   <li>{@code forkjoinpool.steal.count} - 누적 work-stealing count (work 분산 효율)</li>
 * </ul>
 *
 * <h3>검증</h3>
 * <pre>
 * curl http://localhost:8081/actuator/prometheus | grep forkjoinpool
 * # Expected: 3 hits
 * </pre>
 */
@Component
class ForkJoinPoolMetrics(meterRegistry: MeterRegistry) {
    init {
        val pool = ForkJoinPool.commonPool()

        Gauge.builder("forkjoinpool.active.threads") { pool.activeThreadCount.toDouble() }
            .description("ForkJoinPool.commonPool() active thread count (CPU work in flight)")
            .register(meterRegistry)

        Gauge.builder("forkjoinpool.queued.tasks") { pool.queueSize.toDouble() }
            .description("ForkJoinPool.commonPool() queued task count (Dispatchers.Default saturation indicator)")
            .register(meterRegistry)

        Gauge.builder("forkjoinpool.steal.count") { pool.stealCount.toDouble() }
            .description("ForkJoinPool.commonPool() cumulative work-stealing count (work balance efficiency)")
            .register(meterRegistry)
    }
}
