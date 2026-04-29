package maple.expectation.infrastructure.pgmq

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

class PipelineBufferTest {

    @Test
    @DisplayName("offer adds item and returns true when under max")
    fun `offer adds item and returns true when under max`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 10, maxBufferSize = 5)

        val result = buffer.offer("item-1")

        assertThat(result).isTrue()
        assertThat(buffer.size()).isEqualTo(1)
    }

    @Test
    @DisplayName("offer returns false when at max capacity")
    fun `offer returns false when at max capacity`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 10, maxBufferSize = 2)

        buffer.offer("item-1")
        buffer.offer("item-2")
        val result = buffer.offer("item-3")

        assertThat(result).isFalse()
        assertThat(buffer.size()).isEqualTo(2)
    }

    @Test
    @DisplayName("drain returns up to maxItems from buffer")
    fun `drain returns up to maxItems from buffer`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 10, maxBufferSize = 100)

        repeat(5) { buffer.offer("item-$it") }

        val drained = buffer.drain(3)

        assertThat(drained).hasSize(3)
        assertThat(drained).containsExactly("item-0", "item-1", "item-2")
    }

    @Test
    @DisplayName("drain returns fewer items when buffer has less than maxItems")
    fun `drain returns fewer items when buffer has less than maxItems`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 10, maxBufferSize = 100)

        buffer.offer("item-1")
        buffer.offer("item-2")

        val drained = buffer.drain(5)

        assertThat(drained).hasSize(2)
    }

    @Test
    @DisplayName("drain returns empty list when buffer is empty")
    fun `drain returns empty list when buffer is empty`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 10, maxBufferSize = 100)

        val drained = buffer.drain(10)

        assertThat(drained).isEmpty()
    }

    @Test
    @DisplayName("drain removes items from buffer")
    fun `drain removes items from buffer`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 10, maxBufferSize = 100)

        repeat(5) { buffer.offer("item-$it") }

        buffer.drain(3)

        assertThat(buffer.size()).isEqualTo(2)
    }

    @Test
    @DisplayName("isFull returns true when at max capacity")
    fun `isFull returns true when at max capacity`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 10, maxBufferSize = 3)

        buffer.offer("item-1")
        buffer.offer("item-2")
        buffer.offer("item-3")

        assertThat(buffer.isFull()).isTrue()
    }

    @Test
    @DisplayName("drain without args uses microBatchSize")
    fun `drain without args uses microBatchSize`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 3, maxBufferSize = 100)

        repeat(7) { buffer.offer("item-$it") }

        val drained = buffer.drain()

        assertThat(drained).hasSize(3)
        assertThat(buffer.size()).isEqualTo(4)
    }

    @RepeatedTest(3)
    @DisplayName("concurrent offer and drain do not lose items")
    fun `concurrent offer and drain do not lose items`() {
        val buffer = PipelineBuffer<Int>(microBatchSize = 50, maxBufferSize = 10_000)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val producerCount = 4
        val itemsPerProducer = 500
        val totalItems = producerCount * itemsPerProducer
        val startLatch = CountDownLatch(1)
        val producerDoneLatch = CountDownLatch(producerCount)
        val consumerDoneLatch = CountDownLatch(1)
        val collected = java.util.concurrent.ConcurrentLinkedQueue<Int>()

        // Producers: each offers itemsPerProducer items
        repeat(producerCount) { producerId ->
            executor.submit {
                startLatch.await()
                repeat(itemsPerProducer) { i ->
                    buffer.offer(producerId * itemsPerProducer + i)
                }
                producerDoneLatch.countDown()
            }
        }

        // Consumer: drain until all producers done and buffer empty
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
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)

        assertThat(collected.size).isEqualTo(totalItems)
    }
}
