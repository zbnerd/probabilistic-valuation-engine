package maple.pipeline.messaging.dlt

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DltTopologyStatusTest {
    @Test
    fun `missing and undersized DLTs produce create and expand actions`() {
        val status = DltTopologyStatus.evaluate(
            sourceTopics = listOf("source-a", "source-b", "source-c"),
            sourcePartitions = mapOf("source-a" to 3, "source-b" to 5, "source-c" to 2),
            dltPartitions = mapOf("source-b.DLT" to 2, "source-c.DLT" to 2),
            subscriptions = 4,
        )

        assertThat(status.healthy).isFalse()
        assertThat(status.subscriptions).isEqualTo(4)
        assertThat(status.actions).containsExactly(
            DltTopologyAction.CreateDlt("source-a.DLT", 3),
            DltTopologyAction.ExpandDlt("source-b.DLT", 5),
        )
        assertThat(status.missingSources).isEmpty()
    }

    @Test
    fun `equal or larger DLT topology is healthy and never shrinks`() {
        val status = DltTopologyStatus.evaluate(
            sourceTopics = listOf("source-a", "source-b"),
            sourcePartitions = mapOf("source-a" to 3, "source-b" to 2),
            dltPartitions = mapOf("source-a.DLT" to 3, "source-b.DLT" to 8),
        )

        assertThat(status.healthy).isTrue()
        assertThat(status.actions).isEmpty()
    }

    @Test
    fun `missing source is unhealthy and never creates its DLT`() {
        val status = DltTopologyStatus.evaluate(
            sourceTopics = listOf("source-present", "source-missing"),
            sourcePartitions = mapOf("source-present" to 2),
            dltPartitions = emptyMap(),
        )

        assertThat(status.healthy).isFalse()
        assertThat(status.missingSources).containsExactly("source-missing")
        assertThat(status.actions).isEmpty()
    }

    @Test
    fun `topic metadata is bounded and partition counts are positive`() {
        val oversized = "a".repeat(250)

        assertThatThrownBy { DltTopologyAction.CreateDlt(oversized, 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DltTopologyAction.ExpandDlt("source.DLT", 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `no subscriptions is healthy without topic facts`() {
        val status = DltTopologyStatus.evaluate(
            sourceTopics = emptyList(),
            sourcePartitions = emptyMap(),
            dltPartitions = emptyMap(),
            subscriptions = 0,
        )

        assertThat(status.healthy).isTrue()
        assertThat(status.actions).isEmpty()
    }
}
