package maple.pipeline.messaging.dlt

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import maple.pipeline.messaging.contract.CompletionFailures
import maple.pipeline.messaging.contract.PipelineSubscription
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewPartitions
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.errors.InvalidPartitionsException
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationListener

@ConfigurationProperties("pipeline.messaging.dlt-topology")
data class DltTopologyProperties(
    val refreshInterval: Duration = Duration.ofSeconds(30),
    val ensureEnabled: Boolean = true,
) {
    init {
        require(!refreshInterval.isZero && !refreshInterval.isNegative) {
            "pipeline.messaging.dlt-topology.refresh-interval must be positive"
        }
    }
}

class DltTopologyResources(
    private val admin: Admin,
    subscriptions: Collection<PipelineSubscription>,
    private val properties: DltTopologyProperties,
    meterRegistry: MeterRegistry,
) : ApplicationListener<ApplicationReadyEvent>,
    AutoCloseable {
    val subscriptionCount: Int = subscriptions.size
    private val sourceTopics: List<String> = subscriptions
        .flatMap(PipelineSubscription::topics)
        .map(::requireBoundedTopic)
        .distinct()
        .sorted()
    private val status = AtomicReference<DltTopologyStatus?>()
    private val healthyGauge = AtomicInteger()
    private val running = AtomicBoolean(true)
    private val refreshInFlight = AtomicBoolean()

    init {
        meterRegistry.gauge("pipeline.messaging.dlt.topology.healthy", healthyGauge)
    }

    fun lastStatus(): DltTopologyStatus? = status.get()

    fun refresh(): CompletionStage<DltTopologyStatus> {
        if (!running.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("DLT topology resources are closed"))
        }
        val cycle = if (sourceTopics.isEmpty()) {
            CompletableFuture.completedFuture(
                DltTopologyStatus.evaluate(emptyList(), emptyMap(), emptyMap(), subscriptions = 0),
            )
        } else {
            readStatus().thenCompose { initial ->
                if (!properties.ensureEnabled || initial.actions.isEmpty()) {
                    CompletableFuture.completedFuture(initial)
                } else {
                    applyActions(initial.actions).thenCompose { readStatus() }
                }
            }
        }
        cycle.whenComplete { verified, failure ->
            val cached = if (failure == null && verified != null) {
                verified
            } else {
                DltTopologyStatus.failed(
                    subscriptions = subscriptionCount,
                    failure = CompletionFailures.unwrap(
                        failure ?: IllegalStateException("DLT topology cycle returned no status"),
                    ),
                )
            }
            status.set(cached)
            healthyGauge.set(if (cached.healthy) 1 else 0)
        }
        return cycle
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        refreshAndSchedule()
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        admin.close(CLOSE_TIMEOUT)
    }

    private fun readStatus(): CompletionStage<DltTopologyStatus> = readSourcePartitions().thenCompose { sources ->
        val dltTopics = sources.keys.map { source -> "$source.DLT" }
        readDltPartitions(dltTopics).thenApply { dlts ->
            DltTopologyStatus.evaluate(
                sourceTopics = sourceTopics,
                sourcePartitions = sources,
                dltPartitions = dlts,
                subscriptions = subscriptionCount,
            )
        }
    }

    private fun readSourcePartitions(): CompletionStage<Map<String, Int>> {
        val descriptions = runCatching {
            admin.describeTopics(sourceTopics).allTopicNames().toCompletionStage()
        }.getOrElse { failure -> return CompletableFuture.failedFuture(failure) }
        return descriptions.thenApply { topics ->
            sourceTopics.associateWith { topic ->
                val description = topics[topic]
                    ?: throw CompletionException(UnknownTopicOrPartitionException("source topic is missing"))
                description.partitions().size
            }
        }
    }

    private fun readDltPartitions(dltTopics: Collection<String>): CompletionStage<Map<String, Int>> {
        val values = runCatching { admin.describeTopics(dltTopics).topicNameValues() }
            .getOrElse { failure -> return CompletableFuture.failedFuture(failure) }
        val facts = dltTopics.map { topic ->
            val topicFuture = values[topic]
                ?: return CompletableFuture.failedFuture(
                    IllegalStateException("Kafka did not return a DLT topic future"),
                )
            topicFuture.toCompletionStage().handle { description, failure ->
                if (failure == null) {
                    DltPartitionFact(topic, requireNotNull(description).partitions().size)
                } else {
                    val cause = CompletionFailures.unwrap(failure)
                    if (cause is UnknownTopicOrPartitionException) {
                        DltPartitionFact(topic, null)
                    } else {
                        throw CompletionException(cause)
                    }
                }
            }
        }
        return sequence(facts).thenApply { resolved ->
            resolved.mapNotNull { fact -> fact.partitions?.let { fact.topic to it } }.toMap()
        }
    }

    private fun applyActions(actions: Collection<DltTopologyAction>): CompletionStage<Unit> = sequence(
        actions.map(::applyAction),
    ).thenApply { Unit }

    private fun applyAction(action: DltTopologyAction): CompletionStage<Unit> {
        val mutation = runCatching {
            when (action) {
                is DltTopologyAction.CreateDlt -> admin.createTopics(
                    listOf(
                        NewTopic(
                            action.topic,
                            Optional.of(action.partitions),
                            Optional.empty<Short>(),
                        ),
                    ),
                ).all().toCompletionStage()
                is DltTopologyAction.ExpandDlt -> admin.createPartitions(
                    mapOf(action.topic to NewPartitions.increaseTo(action.partitions)),
                ).all().toCompletionStage()
            }
        }.getOrElse { failure -> return CompletableFuture.failedFuture(failure) }
        return mutation.handle { _, failure ->
            if (failure == null) {
                Unit
            } else {
                val cause = CompletionFailures.unwrap(failure)
                val convergenceRace = when (action) {
                    is DltTopologyAction.CreateDlt -> cause is TopicExistsException
                    is DltTopologyAction.ExpandDlt -> cause is InvalidPartitionsException
                }
                if (convergenceRace) Unit else throw CompletionException(cause)
            }
        }
    }

    private fun refreshAndSchedule() {
        if (!running.get() || !refreshInFlight.compareAndSet(false, true)) return
        refresh().whenComplete { verified, failure ->
            refreshInFlight.set(false)
            when {
                failure != null -> log.error(
                    "[DltTopology] reconciliation failed: failureType={}",
                    CompletionFailures.unwrap(failure).javaClass.simpleName,
                )
                verified?.healthy != true -> log.error(
                    "[DltTopology] topology remains unhealthy: pendingActions={} missingSources={}",
                    verified?.actions?.size ?: 0,
                    verified?.missingSources?.size ?: 0,
                )
            }
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        if (!running.get()) return
        CompletableFuture.runAsync(
            { if (running.get()) refreshAndSchedule() },
            CompletableFuture.delayedExecutor(
                properties.refreshInterval.toMillis(),
                TimeUnit.MILLISECONDS,
                Executor(Runnable::run),
            ),
        )
    }

    private fun <T> sequence(stages: Collection<CompletionStage<T>>): CompletionStage<List<T>> = stages.fold(
        CompletableFuture.completedFuture(emptyList()),
    ) { accumulated, stage ->
        accumulated.thenCombine(stage) { values, value -> values + value }
    }

    private data class DltPartitionFact(
        val topic: String,
        val partitions: Int?,
    )

    private companion object {
        private val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val log = LoggerFactory.getLogger(DltTopologyResources::class.java)
    }
}
