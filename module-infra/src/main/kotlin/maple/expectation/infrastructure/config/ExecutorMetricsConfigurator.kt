package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.Collections

/**
 * Executor Metrics Configurator - Micrometer 메트릭 등록 전담 클래스
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>ExecutorServiceMetrics 등록 (완료, 활성, 대기 중인 작업 수)
 *   <li>rejected Counter 등록 (ExecutorServiceMetrics 미제공)
 *   <li>메트릭 태그 설정 (executor 이름)
 * </ul>
 *
 * <h4>Context7 Best Practice</h4>
 *
 * <p>RejectedExecutionHandler에서도 rejected Counter를 등록하지만, 이 클래스는 중앙에서 메트릭 설정을 관리하는 역할을 합니다.
 */
class ExecutorMetricsConfigurator(private val meterRegistry: MeterRegistry) {

  private val log = LoggerFactory.getLogger(ExecutorMetricsConfigurator::class.java)

  /**
   * Executor Service Metrics 등록
   *
   * <h4>제공 메트릭</h4>
   *
   * <ul>
   *   <li>{@code executor.completed} - 완료된 작업 수
   *   <li>{@code executor.active} - 현재 활성 스레드 수
   *   <li>{@code executor.queued} - 큐에 대기 중인 작업 수
   *   <li>{@code executor.pool.size} - 스레드 풀 크기
   * </ul>
   *
   * @param executor ThreadPoolTaskExecutor 인스턴스
   * @param name Executor 이름 (메트릭 태그용)
   */
  fun registerExecutorMetrics(executor: ThreadPoolTaskExecutor, name: String) {
    // 🟥 Red 권고: Micrometer ExecutorServiceMetrics 등록
    ExecutorServiceMetrics(executor.threadPoolExecutor, name, Collections.emptyList())
      .bindTo(meterRegistry)

    log.info("[ExecutorMetrics] 등록 완료: name={}", name)
  }

  /**
   * Rejected Counter 등록 (ExecutorServiceMetrics 미제공)
   *
   * @param name Executor 이름 (메트릭 태그용)
   * @return Counter 인스턴스
   */
  fun createRejectedCounter(name: String): Counter {
    return Counter.builder("executor.rejected")
      .tag("name", name)
      .description("Number of tasks rejected due to queue full")
      .register(meterRegistry)
  }

  /**
   * Custom Gauge 등록 (ThreadPoolExecutor 직접 접근용)
   *
   * @param executor ThreadPoolExecutor 인스턴스
   * @param name Executor 이름 (메트릭 태그용)
   */
  fun registerCustomGauges(executor: java.util.concurrent.ThreadPoolExecutor, name: String) {
    Gauge.builder("executor.queue.size", executor) { e -> e.queue.size.toDouble() }
      .tag("name", name)
      .description("Current queue size")
      .register(meterRegistry)

    Gauge.builder("executor.pool.active", executor) { it.activeCount.toDouble() }
      .tag("name", name)
      .description("Current active thread count")
      .register(meterRegistry)
  }
}
