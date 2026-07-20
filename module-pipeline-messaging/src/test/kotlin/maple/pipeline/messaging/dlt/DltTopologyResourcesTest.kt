package maple.pipeline.messaging.dlt

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.CompletableFuture
import maple.pipeline.messaging.contract.DeliveryHandler
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.pipeline.messaging.contract.PipelineSubscription
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.CreatePartitionsResult
import org.apache.kafka.clients.admin.CreateTopicsResult
import org.apache.kafka.clients.admin.DescribeTopicsResult
import org.apache.kafka.clients.admin.TopicDescription
import org.apache.kafka.common.KafkaFuture
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import org.apache.kafka.common.internals.KafkaFutureImpl
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.actuate.health.Status

class DltTopologyResourcesTest {
    @Test
    fun `missing source fails cycle before DLT reads or mutations`() {
        val admin = mock<Admin>()
        val missingSource = sourceResultFailure(UnknownTopicOrPartitionException("missing"))
        whenever(admin.describeTopics(eq(listOf(SOURCE)))).thenReturn(
            missingSource,
        )
        val resources = resources(admin)

        val refresh = resources.refresh().toCompletableFuture()

        assertThat(refresh).isCompletedExceptionally()
        assertThat(resources.lastStatus()?.healthy).isFalse()
        verify(admin, times(1)).describeTopics(eq(listOf(SOURCE)))
        verify(admin, never()).describeTopics(eq(listOf(DLT)))
        verify(admin, never()).createTopics(any())
        verify(admin, never()).createPartitions(any())
    }

    @Test
    fun `missing DLT is created with source partitions then verified`() {
        val admin = mock<Admin>()
        val initialSource = sourceResult(mapOf(SOURCE to 3))
        val verifiedSource = sourceResult(mapOf(SOURCE to 3))
        val initialDlt = dltResult(mapOf(DLT to DltFact.Missing))
        val verifiedDlt = dltResult(mapOf(DLT to DltFact.Present(3)))
        val createResult = createTopicsResult(KafkaFuture.completedFuture(null))
        whenever(admin.describeTopics(eq(listOf(SOURCE)))).thenReturn(
            initialSource,
            verifiedSource,
        )
        whenever(admin.describeTopics(eq(listOf(DLT)))).thenReturn(
            initialDlt,
            verifiedDlt,
        )
        whenever(admin.createTopics(any())).thenReturn(createResult)
        val resources = resources(admin)

        val status = resources.refresh().toCompletableFuture().resultNow()

        assertThat(status.healthy).isTrue()
        val topics = org.mockito.kotlin.argumentCaptor<Collection<org.apache.kafka.clients.admin.NewTopic>>()
        verify(admin).createTopics(topics.capture())
        assertThat(topics.firstValue.single().name()).isEqualTo(DLT)
        assertThat(topics.firstValue.single().numPartitions()).isEqualTo(3)
        assertThat(topics.firstValue.single().replicationFactor()).isEqualTo(-1)
        verify(admin, never()).createPartitions(any())
    }

    @Test
    fun `undersized DLT expands and authorization failures remain exceptional`() {
        val expandAdmin = mock<Admin>()
        val initialSource = sourceResult(mapOf(SOURCE to 4))
        val verifiedSource = sourceResult(mapOf(SOURCE to 4))
        val initialDlt = dltResult(mapOf(DLT to DltFact.Present(2)))
        val verifiedDlt = dltResult(mapOf(DLT to DltFact.Present(4)))
        val expandResult = createPartitionsResult(KafkaFuture.completedFuture(null))
        whenever(expandAdmin.describeTopics(eq(listOf(SOURCE)))).thenReturn(
            initialSource,
            verifiedSource,
        )
        whenever(expandAdmin.describeTopics(eq(listOf(DLT)))).thenReturn(
            initialDlt,
            verifiedDlt,
        )
        whenever(expandAdmin.createPartitions(any())).thenReturn(expandResult)
        assertThat(resources(expandAdmin).refresh().toCompletableFuture().resultNow().healthy).isTrue()
        verify(expandAdmin).createPartitions(
            org.mockito.kotlin.check { changes ->
                assertThat(changes[DltTopologyResourcesTest.DLT]?.totalCount()).isEqualTo(4)
            },
        )
        verify(expandAdmin, never()).createTopics(any())

        val deniedAdmin = mock<Admin>()
        val deniedSource = sourceResult(mapOf(SOURCE to 1))
        val deniedDlt = dltResult(mapOf(DLT to DltFact.Failed(IllegalStateException("denied"))))
        whenever(deniedAdmin.describeTopics(eq(listOf(SOURCE))))
            .thenReturn(deniedSource)
        whenever(deniedAdmin.describeTopics(eq(listOf(DLT))))
            .thenReturn(deniedDlt)

        val denied = resources(deniedAdmin).refresh().toCompletableFuture()

        assertThat(denied).isCompletedExceptionally()
        verify(deniedAdmin, never()).createTopics(any())
        verify(deniedAdmin, never()).createPartitions(any())
    }

    @Test
    fun `two reconcilers converge when one loses a create race`() {
        val winner = createRaceAdmin(KafkaFuture.completedFuture(null))
        val raceFailure = failedKafkaFuture<Void>(TopicExistsException("created concurrently"))
        val loser = createRaceAdmin(raceFailure)
        val winnerResources = resources(winner)
        val loserResources = resources(loser)

        val winnerStatus = winnerResources.refresh().toCompletableFuture().resultNow()
        val loserStatus = loserResources.refresh().toCompletableFuture().resultNow()

        assertThat(winnerStatus.healthy).isTrue()
        assertThat(loserStatus.healthy).isTrue()
        verify(winner, never()).deleteTopics(any<Collection<String>>())
        verify(loser, never()).deleteTopics(any<Collection<String>>())
        verify(winner, never()).createPartitions(any())
        verify(loser, never()).createPartitions(any())
    }

    @Test
    fun `health is UP for no subscriptions and OUT_OF_SERVICE before first verification`() {
        val emptyAdmin = mock<Admin>()
        val emptyResources = DltTopologyResources(
            admin = emptyAdmin,
            subscriptions = emptyList(),
            properties = DltTopologyProperties(),
            meterRegistry = SimpleMeterRegistry(),
        )
        val pendingResources = resources(mock())

        assertThat(DltTopologyHealthIndicator(emptyResources).health().status).isEqualTo(Status.UP)
        assertThat(DltTopologyHealthIndicator(emptyResources).health().details["subscriptions"]).isEqualTo(0)
        assertThat(DltTopologyHealthIndicator(pendingResources).health().status).isEqualTo(Status.OUT_OF_SERVICE)
    }

    @Test
    fun `close is idempotent and refresh interval must be positive`() {
        val admin = mock<Admin>()
        val resources = resources(admin)

        resources.close()
        resources.close()

        verify(admin, times(1)).close(Duration.ofSeconds(5))
        assertThatThrownBy { DltTopologyProperties(refreshInterval = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun createRaceAdmin(createFuture: KafkaFuture<Void>): Admin = mock<Admin>().also { admin ->
        val initialSource = sourceResult(mapOf(SOURCE to 2))
        val verifiedSource = sourceResult(mapOf(SOURCE to 2))
        val initialDlt = dltResult(mapOf(DLT to DltFact.Missing))
        val verifiedDlt = dltResult(mapOf(DLT to DltFact.Present(2)))
        val createResult = createTopicsResult(createFuture)
        whenever(admin.describeTopics(eq(listOf(SOURCE)))).thenReturn(
            initialSource,
            verifiedSource,
        )
        whenever(admin.describeTopics(eq(listOf(DLT)))).thenReturn(
            initialDlt,
            verifiedDlt,
        )
        whenever(admin.createTopics(any())).thenReturn(createResult)
    }

    private fun resources(admin: Admin): DltTopologyResources = DltTopologyResources(
        admin = admin,
        subscriptions = listOf(subscription()),
        properties = DltTopologyProperties(refreshInterval = Duration.ofSeconds(30), ensureEnabled = true),
        meterRegistry = SimpleMeterRegistry(),
    )

    private fun subscription(): PipelineSubscription = PipelineSubscription(
        id = "test-subscription",
        topics = listOf(SOURCE),
        groupId = "test-group",
        handler = DeliveryHandler { _, _ -> CompletableFuture.completedFuture(DeliveryOutcome.Success) },
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )

    private fun sourceResult(partitions: Map<String, Int>): DescribeTopicsResult = mock<DescribeTopicsResult>().also { result ->
        val descriptions = partitions.mapValues { (topic, count) -> description(topic, count) }
        whenever(result.allTopicNames()).thenReturn(KafkaFuture.completedFuture(descriptions))
    }

    private fun sourceResultFailure(failure: Throwable): DescribeTopicsResult = mock<DescribeTopicsResult>().also { result ->
        whenever(result.allTopicNames()).thenReturn(failedKafkaFuture(failure))
    }

    private fun dltResult(facts: Map<String, DltFact>): DescribeTopicsResult = mock<DescribeTopicsResult>().also { result ->
        val futures = facts.mapValues { (topic, fact) ->
            when (fact) {
                DltFact.Missing -> failedKafkaFuture(UnknownTopicOrPartitionException("missing"))
                is DltFact.Present -> KafkaFuture.completedFuture(description(topic, fact.partitions))
                is DltFact.Failed -> failedKafkaFuture(fact.failure)
            }
        }
        whenever(result.topicNameValues()).thenReturn(futures)
    }

    private fun description(topic: String, partitions: Int): TopicDescription = mock<TopicDescription>().also { description ->
        whenever(description.name()).thenReturn(topic)
        whenever(description.partitions()).thenReturn(List(partitions) { mock() })
    }

    private fun createTopicsResult(future: KafkaFuture<Void>): CreateTopicsResult = mock<CreateTopicsResult>().also { result ->
        whenever(result.all()).thenReturn(future)
    }

    private fun createPartitionsResult(future: KafkaFuture<Void>): CreatePartitionsResult = mock<CreatePartitionsResult>().also { result -> whenever(result.all()).thenReturn(future) }

    private fun <T> failedKafkaFuture(failure: Throwable): KafkaFuture<T> = KafkaFutureImpl<T>().also { future ->
        future.completeExceptionally(failure)
    }

    private sealed interface DltFact {
        data object Missing : DltFact
        data class Present(val partitions: Int) : DltFact
        data class Failed(val failure: Throwable) : DltFact
    }

    private companion object {
        private const val SOURCE = "source-topic"
        private const val DLT = "$SOURCE.DLT"
    }
}
