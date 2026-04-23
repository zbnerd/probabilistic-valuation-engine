package maple.expectation.infrastructure.pgmq

import maple.expectation.common.function.ThrowingSupplier
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
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate

@ExtendWith(MockitoExtension::class)
class PgmqClientBatchArchiveTest {

    @Mock
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var logicExecutor: StubLogicExecutor
    private lateinit var config: PgmqConfig
    private lateinit var client: PgmqClient

    @BeforeEach
    fun setUp() {
        logicExecutor = StubLogicExecutor()
        config = PgmqConfig().apply { transactionCheckEnabled = false }
        client = PgmqClient(jdbcTemplate, com.fasterxml.jackson.databind.ObjectMapper(), logicExecutor, config)
    }

    @Test
    @DisplayName("archiveBatch returns 0 when messageIds is empty")
    fun `archiveBatch returns 0 for empty list`() {
        val result = client.archiveBatch("test_queue", emptyList())
        assertThat(result).isEqualTo(0)
    }

    @Test
    @DisplayName("archiveBatch executes batch DELETE and returns archived count")
    fun `archiveBatch deletes messages and returns count`() {
        whenever(jdbcTemplate.update(any<String>(), any<Array<Any>>())).thenReturn(3)

        val result = client.archiveBatch("test_queue", listOf(1L, 2L, 3L))

        assertThat(result).isEqualTo(3)
        verify(jdbcTemplate).update(any<String>(), any<Array<Any>>())
    }

    @Test
    @DisplayName("archiveBatch returns 0 when no rows matched")
    fun `archiveBatch returns 0 when result is zero`() {
        whenever(jdbcTemplate.update(any<String>(), any<Array<Any>>())).thenReturn(0)

        val result = client.archiveBatch("test_queue", listOf(1L, 2L))

        assertThat(result).isEqualTo(0)
    }

    @Test
    @DisplayName("archiveBatch returns 0 on exception via LogicExecutor")
    fun `archiveBatch returns 0 on exception`() {
        whenever(jdbcTemplate.update(any<String>(), any<Array<Any>>()))
            .thenThrow(RuntimeException("DB error"))

        val result = client.archiveBatch("test_queue", listOf(1L, 2L))

        assertThat(result).isEqualTo(0)
    }

    @Test
    @DisplayName("archiveBatch throws on invalid queue name")
    fun `archiveBatch throws on invalid queue name`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            client.archiveBatch("invalid queue name!", listOf(1L))
        }
    }

    /**
     * Stub LogicExecutor - delegates directly to the task, matching production behavior
     * for executeOrDefault (returns default on exception).
     */
    private class StubLogicExecutor : LogicExecutor {
        override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T = task.get()

        override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T =
            try {
                task.get() ?: defaultValue
            } catch (e: Exception) {
                defaultValue
            }

        override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
            task.run()
        }

        override fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T =
            try {
                task.get()
            } finally {
                finallyBlock.run()
            }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: (Throwable) -> T, context: TaskContext): T =
            try {
                task.get()
            } catch (e: Exception) {
                fallback(e)
            }

        override fun <T> executeWithTranslation(
            task: ThrowingSupplier<T>,
            customTranslator: ExceptionTranslator,
            context: TaskContext,
        ): T =
            try {
                task.get()
            } catch (e: Exception) {
                throw customTranslator.translate(e, context)
            }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T =
            try {
                task.get()
            } catch (e: Exception) {
                recovery(e)
            }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: ExceptionTranslator, context: TaskContext): T =
            try {
                task.get()
            } catch (e: Exception) {
                throw fallback.translate(e, context)
            }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: ExceptionTranslator, context: TaskContext): T =
            try {
                task.get()
            } catch (e: Exception) {
                throw recovery.translate(e, context)
            }

        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            task.run()
        }
    }
}
