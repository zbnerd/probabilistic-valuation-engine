package maple.expectation.infrastructure.external.impl

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.CompletableFuture
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.infrastructure.ratelimit.NexonRateLimiter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
@DisplayName("MetricsNexonApiClientWrapper permit lifecycle tests")
class MetricsNexonApiClientWrapperTest {

    @Mock
    private lateinit var delegate: NexonApiClient

    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var rateLimiter: NexonRateLimiter
    private lateinit var wrapper: MetricsNexonApiClientWrapper

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        rateLimiter = NexonRateLimiter(2, meterRegistry)
        wrapper = MetricsNexonApiClientWrapper(delegate, meterRegistry, rateLimiter, StubLogicExecutor())
    }

    private class StubLogicExecutor : LogicExecutor {
        override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T = task.get()
        override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T = try {
            task.get() ?: defaultValue
        } catch (_: Exception) {
            defaultValue
        }
        override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
            task.run()
        }
        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            task.run()
        }
        override fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T = try {
            task.get()
        } finally {
            finallyBlock.run()
        }
        override fun <T> executeWithTranslation(task: ThrowingSupplier<T>, customTranslator: ExceptionTranslator, context: TaskContext): T = try {
            task.get()
        } catch (e: Exception) {
            throw customTranslator.translate(e, context)
        }
        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: (Throwable) -> T, context: TaskContext): T = try {
            task.get()
        } catch (e: Exception) {
            fallback(e)
        }
        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: ExceptionTranslator, context: TaskContext): T = try {
            task.get()
        } catch (e: Exception) {
            throw fallback.translate(e, context)
        }
        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T = try {
            task.get()
        } catch (e: Exception) {
            recovery(e)
        }
        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: ExceptionTranslator, context: TaskContext): T = try {
            task.get()
        } catch (e: Exception) {
            throw recovery.translate(e, context)
        }
    }

    @Test
    @DisplayName("delegate가 Future를 반환하기 전에 예외를 던져도 permit이 누수되지 않는다")
    fun `should release permit when delegate throws synchronously`() {
        whenever(delegate.getItemDataByOcid("ocid-sync-fail"))
            .thenThrow(IllegalStateException("sync failure"))

        val future = wrapper.getItemDataByOcid("ocid-sync-fail")

        assertThatThrownBy { future.join() }
            .hasCauseInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("sync failure")
        assertThat(rateLimiter.availablePermits()).isEqualTo(2)
    }

    @Test
    @DisplayName("비동기 실패 경로에서도 permit이 정상 반환된다")
    fun `should release permit on async failure`() {
        whenever(delegate.getItemDataByOcid("ocid-async-fail"))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("async failure")))

        val future = wrapper.getItemDataByOcid("ocid-async-fail")

        assertThatThrownBy { future.join() }
            .hasCauseInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("async failure")
        assertThat(rateLimiter.availablePermits()).isEqualTo(2)
    }

    @Test
    @DisplayName("성공 경로에서도 permit이 정상 반환된다")
    fun `should release permit on success`() {
        val response = EquipmentResponse(characterClass = "Warrior")
        whenever(delegate.getItemDataByOcid("ocid-ok"))
            .thenReturn(CompletableFuture.completedFuture(response))

        val result = wrapper.getItemDataByOcid("ocid-ok").join()

        assertThat(result).isEqualTo(response)
        assertThat(rateLimiter.availablePermits()).isEqualTo(2)
    }
}
