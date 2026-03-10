package maple.expectation.infrastructure.lifecycle

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.TimeUnit
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.shutdown.ShutdownProperties
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

@Component
class GracefulShutdownHook(
    @Lazy private val coordinator: ShutdownCoordinator,
    private val executor: LogicExecutor,
    private val properties: ShutdownProperties,
    meterRegistry: MeterRegistry?,
) : SmartLifecycle {

    private val logger = LoggerFactory.getLogger(GracefulShutdownHook::class.java)
    private val shutdownTimer: Timer? = meterRegistry?.let {
        Timer.builder("shutdown.hook.duration")
            .description("Graceful Shutdown Hook 총 소요 시간")
            .register(it)
    }
    private val shutdownSuccessCounter: Counter? = meterRegistry?.let {
        Counter.builder("shutdown.hook.result")
            .tag("status", "success")
            .description("Shutdown 성공 횟수")
            .register(it)
    }
    private val shutdownTimeoutCounter: Counter? = meterRegistry?.let {
        Counter.builder("shutdown.hook.result")
            .tag("status", "timeout")
            .description("Shutdown 타임아웃 횟수")
            .register(it)
    }

    @Volatile
    private var running = false

    override fun start() {
        this.running = true
        logger.debug("[GracefulShutdownHook] Started")
    }

    override fun stop() {
        val context = TaskContext.of("GracefulShutdownHook", "Main")
        val startNanos = System.nanoTime()

        executor.executeWithFinally(
            {
                logger.warn("[GracefulShutdownHook] =============== Shutdown 시작 ===============")

                val completed = executeWithTimeout()

                if (completed) {
                    shutdownSuccessCounter?.increment()
                    logger.warn("[GracefulShutdownHook] =============== Shutdown 완료 ===============")
                } else {
                    shutdownTimeoutCounter?.increment()
                    logger.error("[GracefulShutdownHook] =============== Shutdown 타임아웃 ===============")
                }

                null
            },
            {
                this.running = false
                shutdownTimer?.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
            },
            context,
        )
    }

    private fun executeWithTimeout(): Boolean = executor.executeOrDefault(
        {
            val deadlineNs = System.nanoTime() + Duration.ofSeconds(30).toNanos()

            val coordinatorThread = Thread(
                {
                    try {
                        coordinator.executeShutdown()
                    } catch (e: Exception) {
                        logger.error("[GracefulShutdownHook] Coordinator 실행 실패", e)
                    }
                },
                "shutdown-coordinator",
            )

            coordinatorThread.start()

            var remainingNs: Long
            while ((System.nanoTime() - deadlineNs).also { remainingNs = deadlineNs - System.nanoTime() } > 0) {
                try {
                    TimeUnit.NANOSECONDS.timedJoin(coordinatorThread, remainingNs)
                    if (!coordinatorThread.isAlive) {
                        true // 완료
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.warn("[GracefulShutdownHook] 대기 중 인터럽트")
                    false
                }
            }

            // 타임아웃 발생
            if (coordinatorThread.isAlive) {
                logger.error("[GracefulShutdownHook] Coordinator 타임아웃 - 강제 종료 예정")
                coordinatorThread.interrupt()
                false
            }

            true
        },
        false,
        TaskContext.of("GracefulShutdownHook", "ExecuteWithTimeout"),
    )

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Integer.MAX_VALUE

    override fun isAutoStartup(): Boolean = true
}
