package maple.expectation.core.port.out

import java.time.Duration
import java.util.List
import java.util.concurrent.CompletableFuture

/**
 * Persistence Tracker Strategy Interface (#271 V5 Stateless Architecture)
 *
 * <p>Pluggable strategy for tracking async persistence operations.
 * Implementations vary by storage backend and crash-recovery needs.
 */
interface PersistenceTrackerStrategy {

    /**
     * Register async persistence operation for tracking
     *
     * @param ocid character OCID
     * @param future async operation Future
     * @throws IllegalStateException if shutdown in progress
     */
    fun trackOperation(ocid: String, future: CompletableFuture<Void>)

    /**
     * Wait for all operations to complete (for shutdown)
     *
     * @param timeout max wait time
     * @return true: all completed, false: timeout or already shutdown
     */
    fun awaitAllCompletion(timeout: Duration): Boolean

    /**
     * Get pending OCID list
     *
     * @return pending OCID list
     */
    fun getPendingOcids(): List<String>

    /**
     * Get pending operation count
     *
     * @return pending operation count
     */
    fun getPendingCount(): Int

    /** Reset for testing */
    fun resetForTesting()

    /**
     * Get current strategy type
     *
     * @return strategy type (IN_MEMORY or DISTRIBUTED)
     */
    fun getType(): StrategyType

    /** Persistence tracker strategy type */
    enum class StrategyType {
        /** In-Memory ConcurrentHashMap based (single instance) */
        IN_MEMORY,

        /** Distributed cache SET based (scale-out enabled) */
        DISTRIBUTED,

        /** PostgreSQL regular table based (crash recovery) */
        POSTGRES,
    }
}
