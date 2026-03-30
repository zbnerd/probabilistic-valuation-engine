package maple.expectation.core.port.inbound

import maple.expectation.common.executor.TaskContext

/**
 * Executor Port (ADR-005, Issue #639)
 *
 * <p>Decouples web layer from infrastructure implementation of LogicExecutor.
 * Provides safe execution patterns with proper error handling.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Execute tasks with exception handling</li>
 *   <li>Provide default value on error</li>
 *   <li>Execute void tasks</li>
 *   <li>Exception translation</li>
 * </ul>
 *
 * <h3>Implementation</h3>
 * <ul>
 *   <li>ApplicationExecutionPort in module-app delegates to LogicExecutor</li>
 * </ul>
 *
 * <h3>Migration Path</h3>
 * Replace direct LogicExecutor dependency in controllers with this port.
 */
interface ExecutorPort {

    /**
     * Execute void task with exception handling (Kotlin lambda).
     *
     * @param task The task to execute
     * @param context Execution context for metrics
     */
    fun executeVoid(task: () -> Unit, context: TaskContext)

    /**
     * Execute task and return result, or default value on exception (Kotlin lambda).
     *
     * @param task The task to execute
     * @param defaultValue Value to return if task fails
     * @param context Execution context for metrics
     * @return Task result or default value
     */
    fun <T> executeOrDefault(
        task: () -> T,
        defaultValue: T,
        context: TaskContext,
    ): T

    /**
     * Java-friendly overload for void tasks.
     */
    fun executeVoidJava(task: Runnable, context: TaskContext)

    /**
     * Java-friendly overload for executeOrDefault.
     *
     * @param task The task to execute (may throw checked exceptions)
     * @param defaultValue Value to return if task fails
     * @param context Execution context for metrics
     * @return Task result or default value
     */
    fun <T> executeOrDefaultJava(
        task: ThrowingSupplier<T>,
        defaultValue: T,
        context: TaskContext,
    ): T

    /**
     * Execute task with exception propagation.
     *
     * @param task The task to execute
     * @param context Execution context for metrics
     * @return Task result
     */
    fun <T> execute(task: () -> T, context: TaskContext): T

    /**
     * Execute task with exception translation.
     *
     * @param task The task to execute
     * @param translator Exception translator function
     * @param context Execution context for metrics
     * @return Task result
     */
    fun <T> executeWithTranslation(
        task: () -> T,
        translator: (Throwable, TaskContext) -> Exception,
        context: TaskContext,
    ): T

    /**
     * Throwing supplier for Java interop.
     */
    fun interface ThrowingSupplier<T> {
        @Throws(Throwable::class)
        fun get(): T
    }
}
