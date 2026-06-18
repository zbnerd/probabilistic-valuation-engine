package maple.expectation.infrastructure.pgmq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class ProcessOutcomeTest {
    @Test
    fun `Ack is singleton`() {
        val a1: ProcessOutcome = ProcessOutcome.Ack
        val a2: ProcessOutcome = ProcessOutcome.Ack
        assertThat(a1).isEqualTo(a2)
        assertThat(a1).isInstanceOf(ProcessOutcome.Ack::class.java)
    }

    @Test
    fun `Nack carries retryable and visibilityReset`() {
        val nack = ProcessOutcome.Nack(retryable = true, visibilityReset = Duration.ofSeconds(5))
        assertThat(nack).isInstanceOf(ProcessOutcome.Nack::class.java)
        assertThat(nack.retryable).isTrue()
        assertThat(nack.visibilityReset).isEqualTo(Duration.ofSeconds(5))
    }

    @Test
    fun `Nack supports null visibilityReset`() {
        val nack = ProcessOutcome.Nack(retryable = false, visibilityReset = null)
        assertThat(nack.retryable).isFalse()
        assertThat(nack.visibilityReset).isNull()
    }

    @Test
    fun `DeadLetter carries reason`() {
        val dlq = ProcessOutcome.DeadLetter(reason = "poison message")
        assertThat(dlq).isInstanceOf(ProcessOutcome.DeadLetter::class.java)
        assertThat(dlq.reason).isEqualTo("poison message")
    }

    @Test
    fun `sealed class allows exhaustive when`() {
        val outcomes: List<ProcessOutcome> = listOf(
            ProcessOutcome.Ack,
            ProcessOutcome.Nack(retryable = true, visibilityReset = null),
            ProcessOutcome.DeadLetter("test")
        )
        outcomes.forEach { outcome ->
            val label = when (outcome) {
                is ProcessOutcome.Ack -> "ack"
                is ProcessOutcome.Nack -> "nack"
                is ProcessOutcome.DeadLetter -> "dlq"
            }
            assertThat(label).isNotEmpty
        }
    }
}