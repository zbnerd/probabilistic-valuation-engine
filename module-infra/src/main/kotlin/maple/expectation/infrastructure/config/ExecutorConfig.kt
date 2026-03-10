package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import maple.expectation.infrastructure.executor.CheckedLogicExecutor
import maple.expectation.infrastructure.executor.DefaultCheckedLogicExecutor
import maple.expectation.infrastructure.executor.DefaultLogicExecutor
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.policy.ExecutionPipeline
import maple.expectation.infrastructure.executor.policy.ExecutionPolicy
import maple.expectation.infrastructure.executor.policy.LoggingPolicy
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.annotation.AnnotationAwareOrderComparator
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Executor Configuration - 비동기 실행 및 Thread Pool 관리 설정
 *
 * <h4>책임 (Refactoring 후)</h4>
 *
 * <ul>
 *   <li><b>LogicExecutor Beans</b>: logicExecutor, checkedLogicExecutor, executionPipeline
 *   <li><b>ThreadPoolTaskExecutor Beans</b>: alertTaskExecutor, aiTaskExecutor,
 *       expectationComputeExecutor
 *   <li><b>조정</b>: RejectionPolicyFactory, ExecutorMetricsConfigurator, TaskDecoratorFactory를 활용
 * </ul>
 *
 * <h4>분리된 책임 (별도 클래스)</h4>
 *
 * <ul>
 *   <li>{@link RejectionPolicyFactory}: Rejection Policy 생성 (LOGGING_ABORT_POLICY,
 *       EXPECTATION_ABORT_POLICY)
 *   <li>{@link ExecutorMetricsConfigurator}: Micrometer 메트릭 등록
 *   <li>{@link TaskDecoratorFactory}: MDC + Cache Context 전파용 TaskDecorator 생성
 * </ul>
 *
 * <h4>P2-25 표준화</h4>
 *
 * <p>모든 ThreadPoolTaskExecutor는 {@link ExecutorProperties}를 통해 중앙 집중식 관리됩니다.
 * corePoolSize:maxPoolSize는 항상 1:2 비율을 유지해야 합니다.
 */
@Configuration
@EnableConfigurationProperties(ExecutorLoggingProperties::class, ExecutorProperties::class)
class ExecutorConfig(
  private val meterRegistry: MeterRegistry,
  private val executorProperties: ExecutorProperties
) {

    private val log = LoggerFactory.getLogger(ExecutorConfig::class.java)

  init {
    // P2-25: 시작 시점에 1:2 비율 검증
    executorProperties.validateAll()
    log.info(
      "[ExecutorConfig] P2-25 ThreadPool configuration validated: equipment={}/{}, preset={}/{}, alert={}/{}, expectation={}/{}, async={}/{}",
      executorProperties.equipment.corePoolSize, executorProperties.equipment.maxPoolSize,
      executorProperties.preset.corePoolSize, executorProperties.preset.maxPoolSize,
      executorProperties.alert.corePoolSize, executorProperties.alert.maxPoolSize,
      executorProperties.expectation.corePoolSize, executorProperties.expectation.maxPoolSize,
      executorProperties.async.corePoolSize, executorProperties.async.maxPoolSize
    )
  }

  // ==================== LogicExecutor Beans ====================

  @Bean
  fun exceptionTranslator(): ExceptionTranslator {
    // DefaultLogicExecutor가 기본적으로 사용할 번역기를 지정합니다.
    return ExceptionTranslator.defaultTranslator()
  }

  @Bean
  fun loggingPolicy(props: ExecutorLoggingProperties): LoggingPolicy {
    return LoggingPolicy(props.slowMs)
  }

  @Bean
  @ConditionalOnMissingBean(ExecutionPipeline::class)
  fun executionPipeline(policies: List<ExecutionPolicy>): ExecutionPipeline {
    val ordered = ArrayList(policies)
    AnnotationAwareOrderComparator.sort(ordered)
    return ExecutionPipeline(ordered)
  }

  /**
   * 비즈니스 레이어 기본 Executor (Primary)
   *
   * <p>서비스/도메인 내부에서 기본으로 주입되는 Executor입니다. IO 경계에서는 {@link CheckedLogicExecutor}를
   * {@code @Qualifier("checkedLogicExecutor")}로 opt-in합니다.
   */
  @Bean
  @Primary
  @ConditionalOnMissingBean(LogicExecutor::class)
  fun logicExecutor(pipeline: ExecutionPipeline, translator: ExceptionTranslator): LogicExecutor {
    return DefaultLogicExecutor(pipeline, translator)
  }

  /**
   * IO 경계 전용 CheckedLogicExecutor 빈 등록
   *
   * <p>파일 I/O, 네트워크 통신, 분산 락 등 checked 예외가 발생하는 IO 경계에서 try-catch 없이 예외를 처리합니다.
   *
   * <h4>주입 패턴 (Qualifier 명시 권장)</h4>
   *
   * <p>Lombok {@code @RequiredArgsConstructor}는 {@code @Qualifier}를 생성자 파라미터로 전파하지 않을 수 있으므로, 명시적
   * 생성자를 권장합니다:
   *
   * <pre>{@code
   * class ResilientNexonApiClient {
   *     private final CheckedLogicExecutor checkedExecutor;
   *
   *     ResilientNexonApiClient(
   *         @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor
   *     ) {
   *         this.checkedExecutor = checkedExecutor;
   *     }
   * }
   * }</pre>
   */
  @Bean(name = ["checkedLogicExecutor"])
  @ConditionalOnMissingBean(CheckedLogicExecutor::class)
  fun checkedLogicExecutor(pipeline: ExecutionPipeline): CheckedLogicExecutor {
    return DefaultCheckedLogicExecutor(pipeline)
  }

  // ==================== TaskDecorator Bean ====================

  /**
   * MDC + SkipEquipmentL2CacheContext 전파용 TaskDecorator (위임)
   *
   * @return TaskDecorator 인스턴스
   * @see TaskDecoratorFactory.createContextPropagatingDecorator()
   */
  @Bean
  fun contextPropagatingDecorator(): TaskDecorator {
    return taskDecoratorFactory().createContextPropagatingDecorator()
  }

  // ==================== ThreadPoolTaskExecutor Beans ====================

  /**
   * 외부 알림(Discord/Slack 등) 전용 비동기 Executor
   *
   * <h4>설계 의도</h4>
   *
   * <ul>
   *   <li><b>commonPool 분리</b>: 외부 I/O 지연이 앱 전반의 CompletableFuture에 전파되는 것을 방지
   *   <li><b>Best-effort 알림</b>: 알림은 부가 기능이므로, 폭주 시 드롭/종료 시 즉시 종료
   * </ul>
   *
   * <h4>운영 정책</h4>
   *
   * <ul>
   *   <li><b>RejectedExecution</b>: AbortPolicy + 샘플링 로깅 + rejected 메트릭
   *   <li><b>Shutdown</b>: 대기 없이 즉시 종료 (알림은 flush 불필요)
   * </ul>
   *
   * <h4>P2-25 표준화</h4>
   *
   * <p>설정은 {@code executor.alert} 속성에서 로드됩니다.
   */
  @Bean(name = ["alertTaskExecutor"])
  @ConditionalOnMissingBean(name = ["alertTaskExecutor"])
  fun alertTaskExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
    val config = executorProperties.alert
    val executor = ThreadPoolTaskExecutor()
    executor.corePoolSize = config.corePoolSize
    executor.maxPoolSize = config.maxPoolSize
    executor.queueCapacity = config.queueCapacity
    executor.setThreadNamePrefix("alert-")
    executor.setAllowCoreThreadTimeOut(true)
    executor.setKeepAliveSeconds(30)

    // 불변식 3: ThreadLocal 전파 (P0-4/B2)
    executor.setTaskDecorator(contextPropagatingDecorator)

    // Best-effort 정책: 드롭 허용 + Future 완료 보장 + 메트릭 기록
    executor.setRejectedExecutionHandler(rejectionPolicyFactory().createAlertAbortPolicy())

    // Shutdown 정책: 대기 없이 즉시 종료 (알림은 flush 불필요)
    executor.setWaitForTasksToCompleteOnShutdown(false)

    executor.initialize()

    // Micrometer ExecutorServiceMetrics 등록
    executorMetricsConfigurator().registerExecutorMetrics(executor, "alert")

    log.info(
      "[ExecutorConfig] alertTaskExecutor initialized: core={}, max={}, queue={}",
      config.corePoolSize, config.maxPoolSize, config.queueCapacity
    )

    return executor
  }

  /**
   * AI LLM 호출 전용 Executor (Issue #283 P0-5: Semaphore 제한 외부화)
   *
   * <h4>문제</h4>
   *
   * <p>AiSreService에서 Executors.newVirtualThreadPerTaskExecutor()를 인스턴스 필드로 직접 생성. Bean이 아니므로 관리
   * 불가, 동시성 무제한으로 대량 에러 시 수백 LLM 호출 → OOM 위험.
   *
   * <h4>해결</h4>
   *
   * <ul>
   *   <li>Semaphore로 동시 LLM 호출 제한 (기본값 10, YAML 외부화)
   *   <li>Virtual Thread 사용으로 I/O 대기 시 효율적
   *   <li>Spring Bean으로 관리하여 라이프사이클 추적 가능
   * </ul>
   */
  @Bean(name = ["aiTaskExecutor"])
  fun aiTaskExecutor(
    @org.springframework.beans.factory.annotation.Value("\${ai.sre.max-concurrent-threads:10}")
    maxConcurrent: Int
  ): Executor {
    val semaphore = Semaphore(maxConcurrent)
    val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

    return Executor { runnable ->
      virtualThreadExecutor.execute {
        var acquired = false
        try {
          acquired = semaphore.tryAcquire(10, TimeUnit.SECONDS)
          if (!acquired) {
            log.warn(
              "[AiTaskExecutor] Semaphore timeout - LLM 호출 동시성 한도 초과 (limit={})",
              maxConcurrent
            )
            throw RejectedExecutionException("AI task executor semaphore timeout")
          }
          runnable.run()
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          throw RejectedExecutionException("AI task executor interrupted", e)
        } finally {
          if (acquired) {
            semaphore.release()
          }
        }
      }
    }

    // ==================== LogicExecutor Beans ====================

  /**
   * Default TaskExecutor for @Async methods (Unit 1: P2 Technical Debt)
   *
   * <p>Spring's default SimpleAsyncTaskExecutor creates a new thread for each task without pooling,
   * which can lead to thread exhaustion under load. This custom executor provides proper pooling.
   *
   * <h4>Design Rationale:</h4>
   *
   * <ul>
   *   <li><b>ThreadPoolTaskExecutor</b>: Reusable thread pool with bounded queue
   *   <li><b>CallerRunsPolicy</b>: Backpressure - caller executes task when queue is full
   *   <li><b>Context Propagation</b>: MDC and SecurityContext propagated via TaskDecorator
   *   <li><b>Graceful Shutdown</b>: Wait for in-flight tasks to complete
   * </ul>
   *
   * <h4>P2-25 Standardization</h4>
   *
   * <p>Configuration is loaded from {@code executor.async} properties with 1:2 core:max ratio.
   *
   * @return ThreadPoolTaskExecutor for @Async methods
   * @see EnableAsync
   */
  @Bean(name = ["taskExecutor"])
  @ConditionalOnMissingBean(name = ["taskExecutor"])
  fun taskExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
    val config = executorProperties.async
    val executor = ThreadPoolTaskExecutor()
    executor.corePoolSize = config.corePoolSize
    executor.maxPoolSize = config.maxPoolSize
    executor.queueCapacity = config.queueCapacity
    executor.setThreadNamePrefix("async-")
    executor.setAllowCoreThreadTimeOut(true)
    executor.setKeepAliveSeconds(30)

    // 불변식 3: ThreadLocal 전파 (P0-4/B2)
    executor.setTaskDecorator(contextPropagatingDecorator)

    // CallerRunsPolicy: Backpressure - caller executes task when queue is full
    executor.setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())

    // Graceful Shutdown: 진행 중인 비동기 작업 완료 대기
    executor.setWaitForTasksToCompleteOnShutdown(true)
    executor.setAwaitTerminationSeconds(30)

    executor.initialize()

    // Micrometer ExecutorServiceMetrics 등록
    executorMetricsConfigurator().registerExecutorMetrics(executor, "async")

    log.info(
      "[ExecutorConfig] taskExecutor initialized: core={}, max={}, queue={}",
      config.corePoolSize, config.maxPoolSize, config.queueCapacity
    )

    return executor
  }

  /**
   * Expectation compute(파싱/계산/외부 호출 포함) 데드라인 강제를 위한 전용 Executor
   *
   * <h4>설계 의도</h4>
   *
   * <ul>
   *   <li><b>30초 데드라인 강제</b>: CompletableFuture.orTimeout()과 함께 사용하여 leader compute가 30초를 초과하면
   *       TimeoutException으로 정리
   *   <li><b>inFlight 누수 방지</b>: @Scheduled 백그라운드 정리 대신 실제 데드라인 강제
   * </ul>
   *
   * <h4>P2-25 표준화</h4>
   *
   * <p>설정은 {@code executor.expectation} 속성에서 로드됩니다.
   */
  @Bean(name = ["expectationComputeExecutor"])
  fun expectationComputeExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
    val config = executorProperties.expectation
    val executor = ThreadPoolTaskExecutor()
    executor.corePoolSize = config.corePoolSize
    executor.maxPoolSize = config.maxPoolSize
    executor.queueCapacity = config.queueCapacity
    executor.setThreadNamePrefix("expectation-")
    executor.setAllowCoreThreadTimeOut(true)
    executor.setKeepAliveSeconds(30)

    @Bean
    fun loggingPolicy(props: ExecutorLoggingProperties): LoggingPolicy = LoggingPolicy(props.slowMs)

    @Bean
    @ConditionalOnMissingBean(ExecutionPipeline::class)
    fun executionPipeline(policies: List<ExecutionPolicy>): ExecutionPipeline {
        val ordered = ArrayList(policies)
        AnnotationAwareOrderComparator.sort(ordered)
        return ExecutionPipeline(ordered)
    }

    /**
     * 비즈니스 레이어 기본 Executor (Primary)
     *
     * <p>서비스/도메인 내부에서 기본으로 주입되는 Executor입니다. IO 경계에서는 {@link CheckedLogicExecutor}를
     * {@code @Qualifier("checkedLogicExecutor")}로 opt-in합니다.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(LogicExecutor::class)
    fun logicExecutor(pipeline: ExecutionPipeline, translator: ExceptionTranslator): LogicExecutor = DefaultLogicExecutor(pipeline, translator)

    /**
     * IO 경계 전용 CheckedLogicExecutor 빈 등록
     *
     * <p>파일 I/O, 네트워크 통신, 분산 락 등 checked 예외가 발생하는 IO 경계에서 try-catch 없이 예외를 처리합니다.
     *
     * <h4>주입 패턴 (Qualifier 명시 권장)</h4>
     *
     * <p>Lombok {@code @RequiredArgsConstructor}는 {@code @Qualifier}를 생성자 파라미터로 전파하지 않을 수 있으므로, 명시적
     * 생성자를 권장합니다:
     *
     * <pre>{@code
     * class ResilientNexonApiClient {
     *     private final CheckedLogicExecutor checkedExecutor;
     *
     *     ResilientNexonApiClient(
     *         @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor
     *     ) {
     *         this.checkedExecutor = checkedExecutor;
     *     }
     * }
     * }</pre>
     */
    @Bean(name = ["checkedLogicExecutor"])
    @ConditionalOnMissingBean(CheckedLogicExecutor::class)
    fun checkedLogicExecutor(pipeline: ExecutionPipeline): CheckedLogicExecutor = DefaultCheckedLogicExecutor(pipeline)

    // ==================== TaskDecorator Bean ====================

    log.info(
      "[ExecutorConfig] expectationComputeExecutor initialized: core={}, max={}, queue={}",
      config.corePoolSize, config.maxPoolSize, config.queueCapacity
    )

    return executor
  }

    // ==================== ThreadPoolTaskExecutor Beans ====================

    /**
     * 외부 알림(Discord/Slack 등) 전용 비동기 Executor
     *
     * <h4>설계 의도</h4>
     *
     * <ul>
     *   <li><b>commonPool 분리</b>: 외부 I/O 지연이 앱 전반의 CompletableFuture에 전파되는 것을 방지
     *   <li><b>Best-effort 알림</b>: 알림은 부가 기능이므로, 폭주 시 드롭/종료 시 즉시 종료
     * </ul>
     *
     * <h4>운영 정책</h4>
     *
     * <ul>
     *   <li><b>RejectedExecution</b>: AbortPolicy + 샘플링 로깅 + rejected 메트릭
     *   <li><b>Shutdown</b>: 대기 없이 즉시 종료 (알림은 flush 불필요)
     * </ul>
     *
     * <h4>P2-25 표준화</h4>
     *
     * <p>설정은 {@code executor.alert} 속성에서 로드됩니다.
     */
    @Bean(name = ["alertTaskExecutor"])
    @ConditionalOnMissingBean(name = ["alertTaskExecutor"])
    fun alertTaskExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
        val config = executorProperties.alert
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("alert-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        // 불변식 3: ThreadLocal 전파 (P0-4/B2)
        executor.setTaskDecorator(contextPropagatingDecorator)

        // Best-effort 정책: 드롭 허용 + Future 완료 보장 + 메트릭 기록
        executor.setRejectedExecutionHandler(rejectionPolicyFactory().createAlertAbortPolicy())

        // Shutdown 정책: 대기 없이 즉시 종료 (알림은 flush 불필요)
        executor.setWaitForTasksToCompleteOnShutdown(false)

        executor.initialize()

        // Micrometer ExecutorServiceMetrics 등록
        executorMetricsConfigurator().registerExecutorMetrics(executor, "alert")

        log.info(
            "[ExecutorConfig] alertTaskExecutor initialized: core={}, max={}, queue={}",
            config.corePoolSize,
            config.maxPoolSize,
            config.queueCapacity,
        )

        return executor
    }

    /**
     * AI LLM 호출 전용 Executor (Issue #283 P0-5: Semaphore 제한 외부화)
     *
     * <h4>문제</h4>
     *
     * <p>AiSreService에서 Executors.newVirtualThreadPerTaskExecutor()를 인스턴스 필드로 직접 생성. Bean이 아니므로 관리
     * 불가, 동시성 무제한으로 대량 에러 시 수백 LLM 호출 → OOM 위험.
     *
     * <h4>해결</h4>
     *
     * <ul>
     *   <li>Semaphore로 동시 LLM 호출 제한 (기본값 10, YAML 외부화)
     *   <li>Virtual Thread 사용으로 I/O 대기 시 효율적
     *   <li>Spring Bean으로 관리하여 라이프사이클 추적 가능
     * </ul>
     */
    @Bean(name = ["aiTaskExecutor"])
    fun aiTaskExecutor(
        @org.springframework.beans.factory.annotation.Value("\${ai.sre.max-concurrent-threads:10}")
        maxConcurrent: Int,
    ): Executor {
        val semaphore = Semaphore(maxConcurrent)
        val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

        return Executor { runnable ->
            virtualThreadExecutor.execute {
                var acquired = false
                try {
                    acquired = semaphore.tryAcquire(10, TimeUnit.SECONDS)
                    if (!acquired) {
                        log.warn(
                            "[AiTaskExecutor] Semaphore timeout - LLM 호출 동시성 한도 초과 (limit={})",
                            maxConcurrent,
                        )
                        throw RejectedExecutionException("AI task executor semaphore timeout")
                    }
                    runnable.run()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw RejectedExecutionException("AI task executor interrupted", e)
                } finally {
                    if (acquired) {
                        semaphore.release()
                    }
                }
            }
        }
    }

    /**
     * Async Executor for CompletableFuture operations (ADR-039)
     *
     * <p>Dedicated executor for async operations in controllers (e.g., DonationController). Prevents
     * blocking transactional work from saturating ForkJoinPool.commonPool().
     *
     * <h4>Design Rationale:</h4>
     *
     * <ul>
     *   <li><b>Virtual Threads</b>: Efficient for I/O-bound async operations
     *   <li><b>Unbounded</b>: Virtual threads are lightweight, no queue needed
     *   <li><b>Graceful Shutdown</b>: Wait for in-flight requests to complete
     * </ul>
     *
     * @return ExecutorService using virtual threads
     */
    @Bean(name = ["asyncExecutor"])
    fun asyncExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Default TaskExecutor for @Async methods (Unit 1: P2 Technical Debt)
     *
     * <p>Spring's default SimpleAsyncTaskExecutor creates a new thread for each task without pooling,
     * which can lead to thread exhaustion under load. This custom executor provides proper pooling.
     *
     * <h4>Design Rationale:</h4>
     *
     * <ul>
     *   <li><b>ThreadPoolTaskExecutor</b>: Reusable thread pool with bounded queue
     *   <li><b>CallerRunsPolicy</b>: Backpressure - caller executes task when queue is full
     *   <li><b>Context Propagation</b>: MDC and SecurityContext propagated via TaskDecorator
     *   <li><b>Graceful Shutdown</b>: Wait for in-flight tasks to complete
     * </ul>
     *
     * <h4>P2-25 Standardization</h4>
     *
     * <p>Configuration is loaded from {@code executor.async} properties with 1:2 core:max ratio.
     *
     * @return ThreadPoolTaskExecutor for @Async methods
     * @see EnableAsync
     */
    @Bean(name = ["taskExecutor"])
    @ConditionalOnMissingBean(name = ["taskExecutor"])
    fun taskExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
        val config = executorProperties.async
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("async-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        // 불변식 3: ThreadLocal 전파 (P0-4/B2)
        executor.setTaskDecorator(contextPropagatingDecorator)

        // CallerRunsPolicy: Backpressure - caller executes task when queue is full
        executor.setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())

        // Graceful Shutdown: 진행 중인 비동기 작업 완료 대기
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()

        // Micrometer ExecutorServiceMetrics 등록
        executorMetricsConfigurator().registerExecutorMetrics(executor, "async")

        log.info(
            "[ExecutorConfig] taskExecutor initialized: core={}, max={}, queue={}",
            config.corePoolSize,
            config.maxPoolSize,
            config.queueCapacity,
        )

        return executor
    }

    /**
     * Expectation compute(파싱/계산/외부 호출 포함) 데드라인 강제를 위한 전용 Executor
     *
     * <h4>설계 의도</h4>
     *
     * <ul>
     *   <li><b>30초 데드라인 강제</b>: CompletableFuture.orTimeout()과 함께 사용하여 leader compute가 30초를 초과하면
     *       TimeoutException으로 정리
     *   <li><b>inFlight 누수 방지</b>: @Scheduled 백그라운드 정리 대신 실제 데드라인 강제
     * </ul>
     *
     * <h4>P2-25 표준화</h4>
     *
     * <p>설정은 {@code executor.expectation} 속성에서 로드됩니다.
     */
    @Bean(name = ["expectationComputeExecutor"])
    fun expectationComputeExecutor(contextPropagatingDecorator: TaskDecorator): Executor {
        val config = executorProperties.expectation
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("expectation-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        // 불변식 3: ThreadLocal 전파 (P0-4/B2)
        executor.setTaskDecorator(contextPropagatingDecorator)

        // Issue #168: CallerRunsPolicy → AbortPolicy + rejected 메트릭 기록
        executor.setRejectedExecutionHandler(rejectionPolicyFactory().createExpectationAbortPolicy())

        // Graceful Shutdown: 진행 중인 계산 작업 완료 대기
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()

        // Micrometer ExecutorServiceMetrics 등록
        executorMetricsConfigurator().registerExecutorMetrics(executor, "expectation.compute")

        log.info(
            "[ExecutorConfig] expectationComputeExecutor initialized: core={}, max={}, queue={}",
            config.corePoolSize,
            config.maxPoolSize,
            config.queueCapacity,
        )

        return executor
    }

    // ==================== Helper Factory Beans ====================

    /**
     * Rejection Policy Factory Bean
     *
     * @return RejectionPolicyFactory 인스턴스
     */
    @Bean
    fun rejectionPolicyFactory(): RejectionPolicyFactory = RejectionPolicyFactory(meterRegistry)

    /**
     * Executor Metrics Configurator Bean
     *
     * @return ExecutorMetricsConfigurator 인스턴스
     */
    @Bean
    fun executorMetricsConfigurator(): ExecutorMetricsConfigurator = ExecutorMetricsConfigurator(meterRegistry)

    /**
     * Task Decorator Factory Bean
     *
     * @return TaskDecoratorFactory 인스턴스
     */
    @Bean
    fun taskDecoratorFactory(): TaskDecoratorFactory = TaskDecoratorFactory()
}
