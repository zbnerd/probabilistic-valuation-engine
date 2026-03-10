package maple.expectation.infrastructure.concurrency

import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

/**
 * Single Flight Strategy Interface
 *
 * <p>Abstracts the single flight pattern implementation, allowing different
 * coordination mechanisms (in-memory, PostgreSQL, Redis, etc.).
 *
 * <h4>Purpose</h4>
 * <p>Prevents duplicate execution of identical requests by ensuring that
 * for a given key, only one thread executes while others wait for the result.
 *
 * <h4>Implementations</h4>
 * <ul>
 *   <li>{@code InMemorySingleFlightStrategy}: ConcurrentHashMap-based (single-instance)</li>
 *   <li>{@code PostgresSingleFlightStrategy}: PostgreSQL advisory locks (distributed)</li>
 * </ul>
 *
 * @see PostgresSingleFlightStrategy
 */
interface SingleFlightStrategy {

    /**
     * Execute a synchronous task with single flight guarantee.
     *
     * <p>If multiple threads call this method with the same key concurrently,
     * only one will execute the task. Others will wait and receive the same result.
     *
     * @param T Result type
     * @param key Unique identifier for the request
     * @param supplier Task to execute
     * @return The result of task execution
     */
    fun <T> execute(key: String, supplier: Supplier<T>): T

    /**
     * Execute an asynchronous task with single flight guarantee.
     *
     * <p>If multiple threads call this method with the same key concurrently,
     * only one will execute the task. Others will wait and receive the same result.
     *
     * @param T Result type
     * @param key Unique identifier for the request
     * @param asyncSupplier Async task to execute (returns CompletableFuture)
     * @return CompletableFuture containing the result
     */
    fun <T> executeAsync(key: String, asyncSupplier: Supplier<CompletableFuture<T>>): CompletableFuture<T>
}
