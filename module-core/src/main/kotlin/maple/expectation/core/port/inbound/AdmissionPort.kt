package maple.expectation.core.port.inbound

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture

/**
 * Admission Control Port (ADR-005, Issue #639)
 *
 * <p>Prevents CPU saturation from unique-key fan-out by limiting concurrent cold misses.
 *
 * <p>Provides backpressure mechanism for expensive operations.
 *
 * @see maple.expectation.infrastructure.admission.GlobalAdmissionControl
 */
interface AdmissionPort {

    /**
     * Submit task for admission-controlled execution.
     *
     * <p>Returns immediately (no HTTP thread blocking).
     * If queue is full, returns failed future immediately.
     *
     * @param key Request key (for metrics/logging)
     * @param task Cold-path calculation task
     * @return CompletableFuture with result
     */
    fun <T> submitOrWait(key: String, task: Callable<T>): CompletableFuture<T>
}
