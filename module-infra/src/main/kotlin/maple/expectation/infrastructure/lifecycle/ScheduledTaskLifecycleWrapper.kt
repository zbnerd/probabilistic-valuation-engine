package maple.expectation.infrastructure.lifecycle

import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * Scheduled Task Lifecycle Wrapper (#648)
 *
 * <h3>Purpose</h3>
 * <p>Coordinates graceful shutdown of @Scheduled tasks. Prevents mid-task interruption
 * by tracking active tasks and waiting for drain during shutdown.
 *
 * <h3>Thread Safety</h3>
 * <p>Uses double-check pattern to prevent TOCTOU race condition:
 * <ol>
 *   <li>Fast path: check state == 0 → return false</li>
 *   <li>Increment activeTasks</li>
 *   <li>Re-check state: if stopping → rollback increment, return false</li>
 * </ol>
 *
 * <h3>Phase</h3>
 * <p>Phase 1: drains BEFORE buffer flush (MAX_VALUE - 500) and ShutdownCoordinator (MAX_VALUE).
 * Order: Phase 1 (scheduled drain) → Phase MAX-500 (buffer flush) → Phase MAX (coordinator)
 */
@Component
class ScheduledTaskLifecycleWrapper : SmartLifecycle {
    companion object {
        private val log = LoggerFactory.getLogger(ScheduledTaskLifecycleWrapper::class.java)
        private const val DRAIN_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_NS = 50_000_000L  // 50ms
    }

    // 1 = running, 0 = stopping
    private val state = AtomicInteger(1)
    private val activeTasks = AtomicInteger(0)
    private val completionSignal = AtomicInteger(0)

    /**
     * Call at the start of a @Scheduled method.
     * Returns true if the task should proceed, false if shutdown is in progress.
     */
    fun beforeTask(): Boolean {
        if (state.get() == 0) return false  // fast path: stopping
        activeTasks.incrementAndGet()
        // Double-check: increment 후 stop()이 호출되었는지 재확인
        if (state.get() == 0) {
            activeTasks.decrementAndGet()  // rollback
            return false
        }
        return true
    }

    /**
     * Call at the end of a @Scheduled method (in finally block).
     */
    fun afterTask() {
        val remaining = activeTasks.decrementAndGet()
        if (remaining == 0 && state.get() == 0) {
            // 모든 task 완료 — 대기 중인 stop()에 signal
            completionSignal.incrementAndGet()
        }
    }

    override fun stop() {
        state.set(0)

        if (activeTasks.get() == 0) return

        // activeTasks가 0이 될 때까지 polling (Virtual Thread friendly)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_TIMEOUT_MS)
        while (activeTasks.get() > 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(this, POLL_INTERVAL_NS)
        }

        if (activeTasks.get() > 0) {
            log.warn("[ScheduledTaskLifecycle] Drain timeout: {} tasks still active", activeTasks.get())
        } else {
            log.info("[ScheduledTaskLifecycle] All scheduled tasks drained successfully")
        }
    }

    override fun isRunning(): Boolean = state.get() == 1

    /**
     * Phase 1: Scheduled tasks drain BEFORE buffer flush and coordinator.
     */
    override fun getPhase(): Int = 1

    override fun start() {
        state.set(1)
    }
}
