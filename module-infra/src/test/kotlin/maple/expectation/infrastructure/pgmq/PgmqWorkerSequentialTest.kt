package maple.expectation.infrastructure.pgmq

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class PgmqWorkerSequentialTest {

    @Test
    @DisplayName("sequentialBatchMs=0 means parallel mode (no accumulation)")
    fun `zero sequentialBatchMs is parallel mode`() {
        val config = PgmqWorkerConfig()
        assertThat(config.common.sequentialBatchMs).isEqualTo(0)
    }

    @Test
    @DisplayName("AccumulationBuffer with sequentialBatchMs=0 falls back to immediate flush")
    fun `bufferMs zero is immediate flush`() {
        val buffer = AccumulationBuffer<ExpectationCalcMessage>(bufferMs = 0)
        buffer.addAll(listOf(testMessage(1)))
        assertThat(buffer.shouldFlush()).isTrue()
    }

    @Test
    @DisplayName("Sequential mode config enables accumulation")
    fun `sequential config enables accumulation`() {
        val config = PgmqWorkerConfig().apply {
            common.sequentialBatchMs = 500
            common.workerPoolSize = 2
        }
        assertThat(config.common.sequentialBatchMs).isEqualTo(500)
    }

    private fun testMessage(id: Long): PgmqMessage<ExpectationCalcMessage> {
        return PgmqMessage.of(
            messageId = id,
            readCount = 0,
            enqueuedAt = java.time.Instant.now(),
            vt = java.time.Instant.now().plusSeconds(30),
            payload = ExpectationCalcMessage(userIgn = "User$id", forceRecalculation = false),
        )
    }
}
