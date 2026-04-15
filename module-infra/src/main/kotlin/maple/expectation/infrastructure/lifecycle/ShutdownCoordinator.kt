package maple.expectation.infrastructure.lifecycle

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.locks.LockSupport

@Component
class ShutdownCoordinator(
    private val lifecycleBeans: List<SmartLifecycle>,
    private val executor: LogicExecutor,
    meterRegistry: MeterRegistry?,
) {
    private val logger = LoggerFactory.getLogger(ShutdownCoordinator::class.java)
    private val phaseSuccessCounter: Counter? = meterRegistry?.let {
        Counter.builder("shutdown.coordinator.phase")
            .tag("status", "success")
            .description("Phase 성공 횟수")
            .register(it)
    }
    private val phaseFailureCounter: Counter? = meterRegistry?.let {
        Counter.builder("shutdown.coordinator.phase")
            .tag("status", "failure")
            .description("Phase 실패 횟수")
            .register(it)
    }

    fun executeShutdown() {
        executor.executeVoidJava(
            {
                logger.warn(
                    "[ShutdownCoordinator] =========== 4단계 Shutdown 시작 ({}개 Lifecycle Bean) ===========",
                    lifecycleBeans.size,
                )

                val sortedBeans = lifecycleBeans
                    .filter { it.isRunning }
                    .sortedBy { it.phase }

                logger.info(
                    "[ShutdownCoordinator] Phase 순서: {}",
                    sortedBeans.map { "${it.javaClass.simpleName}(${it.phase})" },
                )

                var phaseIndex = 0
                for (bean in sortedBeans) {
                    phaseIndex++
                    val beanName = bean.javaClass.simpleName
                    val phase = bean.phase

                    logger.info(
                        "[ShutdownCoordinator] Phase [{}/4]: {} 실행 (phase={})",
                        phaseIndex,
                        beanName,
                        phase,
                    )

                    val success = executePhase(bean)
                    if (success) {
                        phaseSuccessCounter?.increment()
                        logger.info("[ShutdownCoordinator] Phase [{}/4]: {} 완료", phaseIndex, beanName)
                    } else {
                        phaseFailureCounter?.increment()
                        logger.error("[ShutdownCoordinator] Phase [{}/4]: {} 실패", phaseIndex, beanName)
                    }
                }

                logger.warn("[ShutdownCoordinator] =========== 4단계 Shutdown 완료 ===========")
            },
            TaskContext.of("ShutdownCoordinator", "Main"),
        )
    }

    private fun executePhase(bean: SmartLifecycle): Boolean {
        return executor.executeOrDefault(
            {
                val beanName = bean.javaClass.simpleName

                try {
                    bean.stop()

                    val deadline = System.currentTimeMillis() + 5000
                    while (bean.isRunning && System.currentTimeMillis() < deadline) {
                        LockSupport.parkNanos(this, 100_000_000L)  // 100ms, Virtual Thread friendly
                    }

                    if (bean.isRunning) {
                        logger.error("[ShutdownCoordinator] {} 타임아웃 (5초 경과)", beanName)
                        return@executeOrDefault false
                    }

                    true
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.error("[ShutdownCoordinator] {} 실행 중 인터럽트", beanName, e)
                    false
                } catch (e: Exception) {
                    logger.error("[ShutdownCoordinator] {} 실행 실패", beanName, e)
                    false
                }
            },
            false,
            TaskContext.of("ShutdownCoordinator", "ExecutePhase", bean.javaClass.simpleName),
        )
    }
}
