package maple.expectation.infrastructure.pgmq

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AccumulationBufferTest {

    private fun testMessage(id: Long = 1L): PgmqMessage<ExpectationCalcMessage> {
        return PgmqMessage.of(
            messageId = id,
            readCount = 0,
            enqueuedAt = Instant.now(),
            vt = Instant.now().plusSeconds(30),
            payload = ExpectationCalcMessage(userIgn = "TestUser$id", forceRecalculation = false),
        )
    }

    @Test
    @DisplayName("shouldFlush returns false when buffer is empty")
    fun `shouldFlush returns false when empty`() {
        val buffer = AccumulationBuffer<ExpectationCalcMessage>(bufferMs = 500)
        assertThat(buffer.shouldFlush()).isFalse()
    }

    @Test
    @DisplayName("shouldFlush returns false before bufferMs elapsed")
    fun `shouldFlush returns false before bufferMs elapsed`() {
        val buffer = AccumulationBuffer<ExpectationCalcMessage>(bufferMs = 5000)
        buffer.addAll(listOf(testMessage(1)))
        assertThat(buffer.shouldFlush()).isFalse()
    }

    @Test
    @DisplayName("shouldFlush returns true after bufferMs elapsed")
    fun `shouldFlush returns true after bufferMs elapsed`() {
        val buffer = AccumulationBuffer<ExpectationCalcMessage>(bufferMs = 10)
        buffer.addAll(listOf(testMessage(1)))
        await().atMost(Duration.ofMillis(200))
            .pollDelay(Duration.ofMillis(5))
            .untilAsserted { assertThat(buffer.shouldFlush()).isTrue() }
    }

    @Test
    @DisplayName("shouldFlush returns true immediately when bufferMs is 0")
    fun `shouldFlush returns true immediately when bufferMs is 0`() {
        val buffer = AccumulationBuffer<ExpectationCalcMessage>(bufferMs = 0)
        buffer.addAll(listOf(testMessage(1)))
        assertThat(buffer.shouldFlush()).isTrue()
    }

    @Test
    @DisplayName("drain returns all messages and resets state")
    fun `drain returns all messages and resets`() {
        val buffer = AccumulationBuffer<ExpectationCalcMessage>(bufferMs = 500)
        buffer.addAll(listOf(testMessage(1), testMessage(2), testMessage(3)))

        val drained = buffer.drain()
        assertThat(drained).hasSize(3)
        assertThat(drained.map { it.messageId }).containsExactly(1L, 2L, 3L)
        assertThat(buffer.isEmpty()).isTrue()
        assertThat(buffer.size()).isZero()
    }

    @Test
    @DisplayName("drain resets firstMessageTime so shouldFlush returns false again")
    fun `drain resets flush timer`() {
        val buffer = AccumulationBuffer<ExpectationCalcMessage>(bufferMs = 10)
        buffer.addAll(listOf(testMessage(1)))
        await().atMost(Duration.ofMillis(200))
            .pollDelay(Duration.ofMillis(5))
            .untilAsserted { assertThat(buffer.shouldFlush()).isTrue() }

        buffer.drain()
        assertThat(buffer.shouldFlush()).isFalse()
    }

    @Test
    @DisplayName("addAll accumulates across multiple calls")
    fun `addAll accumulates across calls`() {
        val buffer = AccumulationBuffer<ExpectationCalcMessage>(bufferMs = 5000)
        buffer.addAll(listOf(testMessage(1)))
        buffer.addAll(listOf(testMessage(2), testMessage(3)))
        assertThat(buffer.size()).isEqualTo(3)
    }

    @Test
    @DisplayName("addAll with empty list is no-op")
    fun `addAll empty list is no-op`() {
        val buffer = AccumulationBuffer<ExpectationCalcMessage>(bufferMs = 500)
        buffer.addAll(emptyList())
        assertThat(buffer.isEmpty()).isTrue()
        assertThat(buffer.shouldFlush()).isFalse()
    }

    @Test
    @DisplayName("firstMessageTime is set only once on first addAll")
    fun `firstMessageTime set only on first addAll`() {
        val buffer = AccumulationBuffer<ExpectationCalcMessage>(bufferMs = 1000)
        buffer.addAll(listOf(testMessage(1)))
        buffer.addAll(listOf(testMessage(2)))
        assertThat(buffer.shouldFlush()).isFalse()
    }
}
