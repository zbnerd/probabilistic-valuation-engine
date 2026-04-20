package maple.expectation.infrastructure.pgmq

import maple.expectation.core.domain.model.character.GameCharacter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PgmqWorkerPipelineTest {

    private fun createCalculationResult(userIgn: String): CalculationResult {
        val message = PgmqMessage.of(
            messageId = 1L,
            readCount = 0,
            enqueuedAt = Instant.now(),
            vt = Instant.now().plusSeconds(30),
            payload = ExpectationCalcMessage(userIgn = userIgn, forceRecalculation = false),
        )
        return CalculationResult(
            message = message,
            response = "ok",
            character = mock(),
        )
    }

    @Test
    @DisplayName("Pipeline buffer accepts Phase 1 CalculationResult and tracks size")
    fun `pipeline buffer accepts Phase 1 results`() {
        val buffer = PipelineBuffer<CalculationResult>(microBatchSize = 10, maxBufferSize = 100)

        val result = createCalculationResult("Player1")
        val accepted = buffer.offer(result)

        assertThat(accepted).isTrue()
        assertThat(buffer.size()).isEqualTo(1)
    }

    @Test
    @DisplayName("Pipeline buffer rejects offer when at max capacity")
    fun `pipeline buffer rejects when full`() {
        val buffer = PipelineBuffer<CalculationResult>(microBatchSize = 10, maxBufferSize = 3)

        repeat(3) { i ->
            buffer.offer(createCalculationResult("Player$i"))
        }
        assertThat(buffer.isFull()).isTrue()

        val accepted = buffer.offer(createCalculationResult("Overflow"))
        assertThat(accepted).isFalse()
        assertThat(buffer.size()).isEqualTo(3)
    }

    @Test
    @DisplayName("Drain batches micro-batch size results, leaving remainder")
    fun `drain batches micro-batch size results`() {
        val buffer = PipelineBuffer<CalculationResult>(microBatchSize = 10, maxBufferSize = 100)

        repeat(4) { i ->
            buffer.offer(createCalculationResult("Player$i"))
        }

        val batch = buffer.drain(3)

        assertThat(batch).hasSize(3)
        assertThat(batch.map { it.message.payload.userIgn })
            .containsExactly("Player0", "Player1", "Player2")
        assertThat(buffer.size()).isEqualTo(1)
    }

    @Test
    @DisplayName("Drain returns partial batch when fewer items available than requested")
    fun `drain returns partial batch when fewer items available`() {
        val buffer = PipelineBuffer<CalculationResult>(microBatchSize = 10, maxBufferSize = 100)

        buffer.offer(createCalculationResult("Solo"))

        val batch = buffer.drain(5)

        assertThat(batch).hasSize(1)
        assertThat(batch[0].message.payload.userIgn).isEqualTo("Solo")
        assertThat(buffer.size()).isZero()
    }

    @Test
    @DisplayName("Concurrent offers and drains maintain data integrity with CalculationResult")
    fun `concurrent offers and drains maintain data integrity`() {
        val buffer = PipelineBuffer<CalculationResult>(microBatchSize = 50, maxBufferSize = 10_000)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val producerCount = 4
        val itemsPerProducer = 50
        val totalItems = producerCount * itemsPerProducer
        val startLatch = CountDownLatch(1)
        val producerDoneLatch = CountDownLatch(producerCount)
        val consumerDoneLatch = CountDownLatch(1)
        val collected = java.util.concurrent.ConcurrentLinkedQueue<CalculationResult>()

        // Producers: each offers CalculationResult items
        repeat(producerCount) { producerId ->
            executor.submit {
                startLatch.await()
                repeat(itemsPerProducer) { i ->
                    buffer.offer(createCalculationResult("P${producerId}_$i"))
                }
                producerDoneLatch.countDown()
            }
        }

        // Consumer: drains until all producers done and buffer empty
        executor.submit {
            startLatch.await()
            while (producerDoneLatch.count > 0 || buffer.size() > 0) {
                val batch = buffer.drain(50)
                collected.addAll(batch)
                if (batch.isEmpty() && producerDoneLatch.count > 0) {
                    Thread.yield()
                }
            }
            consumerDoneLatch.countDown()
        }

        startLatch.countDown()
        producerDoneLatch.await()
        consumerDoneLatch.await()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertThat(collected.size).isEqualTo(totalItems)
        // Verify no duplicate userIgn values
        val igns = collected.map { it.message.payload.userIgn }.toSet()
        assertThat(igns).hasSize(totalItems)
    }
}
