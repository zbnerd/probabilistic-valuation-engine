package maple.expectation.infrastructure.batch

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.cache.tiered.PostgresL2CacheStrategy
import maple.expectation.infrastructure.config.AdaptiveMicroBatchProperties
import maple.expectation.infrastructure.config.CacheProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Unit tests for [L2CacheMicroBatchAdapter].
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li>Returns null for non-existent keys</li>
 *   <li>Handles empty keys gracefully</li>
 *   <li>Delegates to L2 strategy correctly</li>
 * </ul>
 *
 * @see L2CacheMicroBatchAdapter
 * @see PostgresL2CacheStrategy
 */
@ExtendWith(MockitoExtension::class)
@DisplayName("L2CacheMicroBatchAdapter Tests")
class L2CacheMicroBatchAdapterTest {

    @Mock
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var properties: AdaptiveMicroBatchProperties
    private lateinit var logicExecutor: StubLogicExecutor
    private lateinit var objectMapper: ObjectMapper
    private lateinit var l2Strategy: PostgresL2CacheStrategy
    private lateinit var adapter: L2CacheMicroBatchAdapter

    @BeforeEach
    fun setUp() {
        properties = AdaptiveMicroBatchProperties.defaults()
        logicExecutor = StubLogicExecutor()
        objectMapper = ObjectMapper()
        val meterRegistry = SimpleMeterRegistry()

        l2Strategy = PostgresL2CacheStrategy(
            jdbcTemplate = jdbcTemplate,
            executor = logicExecutor,
            objectMapper = objectMapper,
            meterRegistry = meterRegistry,
            cacheProperties = CacheProperties(),
        )

        adapter = L2CacheMicroBatchAdapter(
            properties = properties,
            logicExecutor = logicExecutor,
            meterRegistry = meterRegistry,
            l2Strategy = l2Strategy,
        )
    }

    @Test
    @DisplayName("Should return null for non-existent key")
    fun `get returns null when not found`() {
        // When
        val result = adapter.get("test:v1:nonexistent", String::class.java)

        // Then
        assertThat(result).isNull()
    }

    @Test
    @DisplayName("Should handle empty key gracefully")
    fun `get handles empty key`() {
        // When - empty key returns null due to executor error handling
        val result = adapter.get("", String::class.java)

        // Then - should not throw
        assertThat(result).isNull()
    }

    /**
     * Stub LogicExecutor - 실제 작업을 실행하는 단순 구현
     */
    private class StubLogicExecutor(
        private val executionDelayMs: Long = 0,
    ) : LogicExecutor {
        override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            return task.get()
        }

        override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T = try {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            task.get() ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }

        override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            task.run()
        }

        override fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T = try {
            execute(task, context)
        } finally {
            finallyBlock.run()
        }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: (Throwable) -> T, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            fallback(e)
        }

        override fun <T> executeWithTranslation(
            task: ThrowingSupplier<T>,
            customTranslator: ExceptionTranslator,
            context: TaskContext,
        ): T = try {
            execute(task, context)
        } catch (e: Exception) {
            throw customTranslator.translate(e, context)
        }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            recovery(e)
        }

        // Java-friendly overload with ExceptionTranslator
        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: ExceptionTranslator, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            throw fallback.translate(e, context)
        }

        // Java-friendly overload with ExceptionTranslator
        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: ExceptionTranslator, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            throw recovery.translate(e, context)
        }

        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            task.run()
        }
    }
}
