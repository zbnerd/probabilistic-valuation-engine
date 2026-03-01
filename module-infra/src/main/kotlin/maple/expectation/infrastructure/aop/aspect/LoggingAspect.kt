package maple.expectation.infrastructure.aop.aspect

import maple.expectation.infrastructure.aop.collector.PerformanceStatisticsCollector
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory

/**
 * 실행 시간 로깅 Aspect (TaskContext 및 평탄화 적용)
 *
 * <h3>#271 V5 Stateless Architecture</h3>
 *
 * <p>SmartLifecycle을 구현하여 Graceful Shutdown 시 통계를 출력합니다. Phase가 낮아 다른 컴포넌트보다 나중에 종료됩니다.
 *
 * <h3>Issue #283 P0-6: Scale-out Safety</h3>
 *
 * <p>{@code running} 플래그는 인스턴스별 SmartLifecycle 상태로, 분산 환경에서 각 인스턴스가 독립적으로 관리하는 것이 올바른 설계입니다.
 */
@Aspect
@Component
class LoggingAspect(
    private val statsCollector: PerformanceStatisticsCollector,
    private val executor: LogicExecutor
) : SmartLifecycle {

    companion object {
        private val log = LoggerFactory.getLogger(LoggingAspect::class.java)
    }

    @Volatile
    private var running: Boolean = false

    /**
     * 메서드 실행 시간 로깅 (코드 평탄화 적용)
     *
     * <p>TaskContext를 통해 메트릭 카디널리티를 통제하며 체크 예외 노이즈를 제거합니다.
     */
    @Around("@annotation(maple.expectation.aop.annotation.LogExecutionTime)")
    fun logExecutionTime(joinPoint: ProceedingJoinPoint): Any? {
        // Issue #283 P0-6: Graceful Shutdown 중에는 성능 기록 스킵
        if (!running) {
            return executor.execute(
                { joinPoint.proceed() },
                TaskContext.of("Logging", "ShutdownBypass")
            )
        }

        val methodName = joinPoint.signature.toShortString()
        val start = System.currentTimeMillis()

        // ✅ 수정: String 대신 TaskContext 사용 (Component="Logging", Operation="ExecutionTime")
        val context = TaskContext.of("Logging", "ExecutionTime", methodName)

        return executor.executeWithFinally(
            { joinPoint.proceed() },
            { recordExecutionTime(methodName, start) },
            context
        )
    }

    /** 실행 시간 기록 (평탄화: 별도 메서드로 분리) */
    private fun recordExecutionTime(methodName: String, start: Long) {
        val executionTime = System.currentTimeMillis() - start
        statsCollector.addTime(methodName, executionTime)
    }

    fun getStatistics(testName: String): Array<String> {
        return statsCollector.calculateStatistics(testName)
    }

    fun resetStatistics() {
        log.warn("🔄 Micrometer 통계는 수동으로 리셋되지 않습니다. Prometheus 대시보드를 확인하세요.")
    }

    // ==================== SmartLifecycle Implementation ====================

    override fun start() {
        this.running = true
        log.debug("[LoggingAspect] Started")
    }

    /**
     * Graceful Shutdown 시 최종 통계 출력
     *
     * <p>#271 V5: @PreDestroy 대신 SmartLifecycle.stop() 사용
     */
    override fun stop() {
        printFinalStatistics()
        this.running = false
    }

    /** 최종 통계 출력 (내부 헬퍼) */
    private fun printFinalStatistics() {
        val stats = statsCollector.calculateStatistics("애플리케이션 전체 운영")
        log.info("========================================================")
        for (stat in stats) {
            log.info(stat)
        }
        log.info("========================================================")
    }

    override fun isRunning(): Boolean {
        return running
    }

    /**
     * 다른 Shutdown 컴포넌트보다 나중에 종료 (낮은 phase)
     *
     * <p>GracefulShutdownCoordinator (MAX-1000) 이후 실행
     */
    override fun getPhase(): Int {
        return Int.MAX_VALUE - 2000
    }

    override fun isAutoStartup(): Boolean {
        return true
    }
}
