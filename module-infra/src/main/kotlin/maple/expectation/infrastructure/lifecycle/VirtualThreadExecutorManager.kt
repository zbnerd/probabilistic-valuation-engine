package maple.expectation.infrastructure.lifecycle

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Manages virtual thread executor lifecycle with standardized shutdown.
 *
 * Usage:
 * ```
 * private val exec = VirtualThreadExecutorManager("MyComponent")
 * // use exec.executor for task submission
 *
 * @PreDestroy fun shutdown() = exec.shutdown()
 * ```
 */
class VirtualThreadExecutorManager(private val componentName: String) {
    val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    fun shutdown() {
        executor.shutdown()
        if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("[{}] VT executor did not terminate in {}s", componentName, SHUTDOWN_TIMEOUT_SECONDS)
            executor.shutdownNow()
        }
        log.info("[{}] VT executor shut down", componentName)
    }

    companion object {
        private val log = LoggerFactory.getLogger(VirtualThreadExecutorManager::class.java)
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
