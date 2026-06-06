package maple.synchronizer.state

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class ChunkExecutionStatusTest {

    private val now: Instant = Instant.parse("2026-06-06T12:00:00Z")
    private val future: Instant = now.plusSeconds(60)
    private val past: Instant = now.minusSeconds(60)

    @Test
    fun `fromName round-trips all four non-pending names`() {
        assertThat(ChunkExecutionStatus.fromName("PROCESSING")).isEqualTo(ChunkExecutionStatus.Processing)
        assertThat(ChunkExecutionStatus.fromName("SUCCEEDED")).isEqualTo(ChunkExecutionStatus.Succeeded)
        assertThat(ChunkExecutionStatus.fromName("FAILED_RETRYABLE")).isEqualTo(ChunkExecutionStatus.FailedRetryable(null))
        assertThat(ChunkExecutionStatus.fromName("FAILED_TERMINAL")).isEqualTo(ChunkExecutionStatus.FailedTerminal(null))
    }

    @Test
    fun `fromName throws IllegalArgumentException on unknown value`() {
        val ex = assertThrows<IllegalArgumentException> {
            ChunkExecutionStatus.fromName("BOGUS")
        }
        assertThat(ex.message).contains("BOGUS")
    }

    @Test
    fun `Processing is not terminal and does not preserve redelivery`() {
        val s = ChunkExecutionStatus.Processing
        assertThat(s.isTerminal()).isFalse()
        assertThat(s.shouldAckSkip(now)).isFalse()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isFalse()
    }

    @Test
    fun `Processing isReclaimed is true when lease is null or expired`() {
        val s = ChunkExecutionStatus.Processing
        assertThat(s.isReclaimed(leaseUntil = null, now = now)).isTrue()
        assertThat(s.isReclaimed(leaseUntil = past, now = now)).isTrue()
    }

    @Test
    fun `Processing isReclaimed is false when lease is in the future`() {
        val s = ChunkExecutionStatus.Processing
        assertThat(s.isReclaimed(leaseUntil = future, now = now)).isFalse()
    }

    @Test
    fun `Succeeded is terminal and always ack-skips`() {
        val s = ChunkExecutionStatus.Succeeded
        assertThat(s.isTerminal()).isTrue()
        assertThat(s.isTerminalSkip()).isTrue()
        assertThat(s.shouldAckSkip(now)).isTrue()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isFalse()
    }

    @Test
    fun `FailedTerminal is terminal and always ack-skips`() {
        val s = ChunkExecutionStatus.FailedTerminal("MAX_ATTEMPTS_EXCEEDED")
        assertThat(s.isTerminal()).isTrue()
        assertThat(s.isTerminalSkip()).isTrue()
        assertThat(s.shouldAckSkip(now)).isTrue()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isFalse()
    }

    @Test
    fun `FailedRetryable with future retry preserves redelivery and does not ack-skip`() {
        val s = ChunkExecutionStatus.FailedRetryable(future)
        assertThat(s.isTerminal()).isFalse()
        assertThat(s.shouldAckSkip(now)).isFalse()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isTrue()
    }

    @Test
    fun `FailedRetryable with past retry does not preserve redelivery and ack-skips`() {
        val s = ChunkExecutionStatus.FailedRetryable(past)
        assertThat(s.shouldAckSkip(now)).isTrue()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isFalse()
    }

    @Test
    fun `FailedRetryable with null nextRetryAt ack-skips immediately`() {
        val s = ChunkExecutionStatus.FailedRetryable(null)
        assertThat(s.shouldAckSkip(now)).isTrue()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isFalse()
    }

    @Test
    fun `PENDING_NAME constant equals PENDING`() {
        assertThat(ChunkExecutionStatus.PENDING_NAME).isEqualTo("PENDING")
    }

    @Test
    fun `name property on each subtype matches NAME constant`() {
        assertThat(ChunkExecutionStatus.Processing.name).isEqualTo("PROCESSING")
        assertThat(ChunkExecutionStatus.Succeeded.name).isEqualTo("SUCCEEDED")
        assertThat(ChunkExecutionStatus.FailedRetryable(null).name).isEqualTo("FAILED_RETRYABLE")
        assertThat(ChunkExecutionStatus.FailedTerminal(null).name).isEqualTo("FAILED_TERMINAL")
    }
}
