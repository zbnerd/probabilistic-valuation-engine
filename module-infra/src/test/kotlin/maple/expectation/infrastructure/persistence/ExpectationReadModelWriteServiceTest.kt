package maple.expectation.infrastructure.persistence

import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.persistence.repository.ExpectationReadModelRepository
import maple.expectation.util.GzipUtils
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * Unit tests for [ExpectationReadModelWriteService].
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li>Compresses JSON using GZIP before writing</li>
 *   <li>Produces valid GZIP with magic bytes (0x1f 0x8b)</li>
 *   <li>Calls repository upsertNative with correct parameters</li>
 * </ul>
 *
 * @see ExpectationReadModelWriteService
 * @see ExpectationReadModelRepository
 */
@ExtendWith(MockitoExtension::class)
class ExpectationReadModelWriteServiceTest {

    @Mock
    private lateinit var repository: ExpectationReadModelRepository

    private lateinit var service: ExpectationReadModelWriteService

    @BeforeEach
    fun setUp() {
        service = ExpectationReadModelWriteService(repository, executor)
    }

    @Test
    fun `writeToReadModel compresses JSON and calls upsertNative`() {
        val userIgn = "testUser"
        val json = """{"userIgn":"testUser","totalExpectedCost":100}"""
        val calculatedAt = Instant.now()

        service.writeToReadModel(userIgn, json, calculatedAt)

        val payloadCaptor = argumentCaptor<ByteArray>()
        verify(repository).upsertNative(
            eq(userIgn),
            payloadCaptor.capture(),
            eq(calculatedAt),
        )

        val payload = payloadCaptor.firstValue
        assertTrue(payload.size >= 2)
        assert(payload[0] == 0x1f.toByte())
        assert(payload[1] == 0x8b.toByte())

        val decompressed = GzipUtils.decompress(payload)
        assert(decompressed == json)
    }

    @Test
    fun `writeToReadModel produces valid GZIP with magic bytes`() {
        val userIgn = "testUser"
        val json = """{"test":"data"}"""
        val calculatedAt = Instant.now()

        service.writeToReadModel(userIgn, json, calculatedAt)

        val payloadCaptor = argumentCaptor<ByteArray>()
        verify(repository).upsertNative(
            eq(userIgn),
            payloadCaptor.capture(),
            eq(calculatedAt),
        )

        val payload = payloadCaptor.firstValue
        assertTrue(payload.size >= 2)
        assert(payload[0] == 0x1f.toByte())
        assert(payload[1] == 0x8b.toByte())
    }

    @Test
    fun `writeToReadModel propagates repository exception`() {
        val userIgn = "testUser"
        val json = """{"test":"data"}"""
        val calculatedAt = Instant.now()

        whenever(repository.upsertNative(eq(userIgn), any(), eq(calculatedAt)))
            .thenThrow(RuntimeException("DB connection failed"))

        val exception = org.junit.jupiter.api.assertThrows<RuntimeException> {
            service.writeToReadModel(userIgn, json, calculatedAt)
        }
        assert(exception.message?.contains("DB connection failed") == true)
    }

    /**
     * Simple pass-through LogicExecutor that directly invokes lambdas.
     */
    private val executor: LogicExecutor = object : LogicExecutor {
        override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T =
            try { task.get() } catch (e: Throwable) { throw e as RuntimeException }

        override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T =
            try { task.get() } catch (_: Throwable) { defaultValue }

        override fun <T> executeWithTranslation(task: ThrowingSupplier<T>, customTranslator: ExceptionTranslator, context: TaskContext): T =
            try { task.get() } catch (e: Throwable) { throw customTranslator.translate(e, context) as RuntimeException }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: (Throwable) -> T, context: TaskContext): T =
            try { task.get() } catch (e: Throwable) { fallback(e) }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: ExceptionTranslator, context: TaskContext): T =
            try { task.get() } catch (e: Throwable) { throw fallback.translate(e, context) as RuntimeException }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T =
            try { task.get() } catch (e: Throwable) { recovery(e) }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: ExceptionTranslator, context: TaskContext): T =
            try { task.get() } catch (e: Throwable) { throw recovery.translate(e, context) as RuntimeException }

        override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
            try { task.run() } catch (e: Throwable) { throw e as RuntimeException }
        }

        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            task.run()
        }

        override fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T =
            try { task.get() } finally { finallyBlock.run() }
    }
}
