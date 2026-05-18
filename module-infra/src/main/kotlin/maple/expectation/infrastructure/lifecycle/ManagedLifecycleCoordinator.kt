package maple.expectation.infrastructure.lifecycle

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class ManagedLifecycleCoordinator(
    private val lifecycles: List<ManagedLifecycle>,
    private val executor: LogicExecutor,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)

    override fun start() {
        executor.executeVoid(
            {
                lifecycles.sortedBy { it.lifecyclePhase }.forEach { lifecycle ->
                    executor.executeOrDefault(
                        {
                            lifecycle.startLifecycle()
                            Unit
                        },
                        Unit,
                        TaskContext.of("ManagedLifecycle", "Start", lifecycle.lifecycleName),
                    )
                }
                running.set(true)
                log.info(
                    "[ManagedLifecycle] started: count={} components={}",
                    lifecycles.size,
                    lifecycles.map { "${it.lifecycleName}(phase=${it.lifecyclePhase})" },
                )
            },
            TaskContext.of("ManagedLifecycle", "StartAll"),
        )
    }

    override fun stop() {
        stop { }
    }

    override fun stop(callback: Runnable) {
        executor.executeWithFinally(
            {
                lifecycles.sortedByDescending { it.lifecyclePhase }.forEach { lifecycle ->
                    executor.executeOrDefault(
                        {
                            lifecycle.stopLifecycle()
                            Unit
                        },
                        Unit,
                        TaskContext.of("ManagedLifecycle", "Stop", lifecycle.lifecycleName),
                    )
                }
                null
            },
            {
                running.set(false)
                callback.run()
                log.info("[ManagedLifecycle] stopped: count={}", lifecycles.size)
            },
            TaskContext.of("ManagedLifecycle", "StopAll"),
        )
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 100
}
