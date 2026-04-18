package maple.expectation.infrastructure.fanout

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.core.port.out.FanOutQueuePort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClientResponseException

@DisplayName("NexonFanOutBatchLoader Tests")
class NexonFanOutBatchLoaderTest {

    private val nexonApiClient: NexonApiClient = mock()
    private val fanOutQueuePort: FanOutQueuePort = mock()

    private val executor: LogicExecutor = ImmediateLogicExecutor()

    private lateinit var sut: NexonFanOutBatchLoader

    private val sampleResponse = EquipmentResponse(characterClass = "Archer")

    @AfterEach
    fun tearDown() {
        if (::sut.isInitialized) {
            sut.shutdown()
        }
    }

    @Test
    @DisplayName("빈 OCID 목록이면 빈 결과를 반환한다")
    fun `load returns empty map when ocids is empty`() {
        sut = NexonFanOutBatchLoader(nexonApiClient, fanOutQueuePort, executor)

        val result = sut.load(emptyList())

        assertThat(result).isEmpty()
        verify(nexonApiClient, never()).getItemDataByOcid(any())
        verify(fanOutQueuePort, never()).enqueue(any(), any(), any())
    }

    @Test
    @DisplayName("정상 응답은 OCID-장비 매핑으로 반환한다")
    fun `load returns successful responses`() {
        sut = NexonFanOutBatchLoader(nexonApiClient, fanOutQueuePort, executor)
        whenever(nexonApiClient.getItemDataByOcid("ocid-1")).thenReturn(CompletableFuture.completedFuture(sampleResponse))

        val result = sut.load(listOf("ocid-1"))

        assertThat(result).containsEntry("ocid-1", sampleResponse)
        verify(fanOutQueuePort, never()).enqueue(any(), any(), any())
    }

    @Test
    @DisplayName("429 에러면 재시도 큐에 enqueue 하고 결과에서 제외한다")
    fun `load enqueues retry when 429 occurs`() {
        sut = NexonFanOutBatchLoader(nexonApiClient, fanOutQueuePort, executor)
        val tooManyRequests = WebClientResponseException.create(
            429,
            "Too Many Requests",
            HttpHeaders.EMPTY,
            "rate limited".toByteArray(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
        )
        whenever(nexonApiClient.getItemDataByOcid("ocid-429")).thenReturn(CompletableFuture.failedFuture(tooManyRequests))

        val result = sut.load(listOf("ocid-429"))

        assertThat(result).isEmpty()
        verify(fanOutQueuePort, times(1)).enqueue(eq("ocid-429"), eq("batch"), any())
    }

    @Test
    @DisplayName("429가 아닌 에러면 enqueue 하지 않고 결과에서 제외한다")
    fun `load does not enqueue retry when non-429 error occurs`() {
        sut = NexonFanOutBatchLoader(nexonApiClient, fanOutQueuePort, executor)
        whenever(nexonApiClient.getItemDataByOcid("ocid-500"))
            .thenReturn(CompletableFuture.failedFuture(IllegalStateException("boom")))

        val result = sut.load(listOf("ocid-500"))

        assertThat(result).isEmpty()
        verify(fanOutQueuePort, never()).enqueue(any(), any(), any())
    }

    @Test
    @DisplayName("is429는 CompletionException 래핑 체인에서도 429를 감지한다")
    fun `is429 detects wrapped WebClientResponseException`() {
        val tooManyRequests = WebClientResponseException.create(
            429,
            "Too Many Requests",
            HttpHeaders.EMPTY,
            byteArrayOf(),
            null,
        )
        val wrapped = RuntimeException("outer", RuntimeException("middle", tooManyRequests))

        val result = NexonFanOutBatchLoader.is429(wrapped)

        assertThat(result).isTrue()
    }

    private class ImmediateLogicExecutor : LogicExecutor {
        override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T = task.get()

        override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T =
            runCatching { task.get() ?: defaultValue }.getOrElse { defaultValue }

        override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
            task.run()
        }

        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            task.run()
        }

        override fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T =
            try {
                task.get()
            } finally {
                finallyBlock.run()
            }

        override fun <T> executeWithTranslation(
            task: ThrowingSupplier<T>,
            customTranslator: ExceptionTranslator,
            context: TaskContext,
        ): T = try {
            task.get()
        } catch (e: Exception) {
            throw customTranslator.translate(e, context)
        }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: (Throwable) -> T, context: TaskContext): T =
            runCatching { task.get() }.getOrElse { fallback(it) }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: ExceptionTranslator, context: TaskContext): T =
            try {
                task.get()
            } catch (e: Exception) {
                throw fallback.translate(e, context)
            }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T =
            runCatching { task.get() }.getOrElse { recovery(it) }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: ExceptionTranslator, context: TaskContext): T =
            try {
                task.get()
            } catch (e: Exception) {
                throw recovery.translate(e, context)
            }
    }
}
