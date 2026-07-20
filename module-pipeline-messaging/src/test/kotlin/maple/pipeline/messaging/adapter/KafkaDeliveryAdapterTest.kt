package maple.pipeline.messaging.adapter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Delayed
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryHandler
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.pipeline.messaging.contract.PipelineSubscription
import maple.pipeline.messaging.dlt.DltRecordFactory
import maple.pipeline.messaging.dlt.DltRecordSanitizer
import maple.pipeline.messaging.dlt.DltPublisher
import maple.pipeline.messaging.metrics.DeliveryMetrics
import maple.pipeline.messaging.policy.DeliveryRetryPolicy
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class KafkaDeliveryAdapterTest {
    private val ownedExecutors = mutableListOf<ExecutorService>()

    @AfterEach
    fun stopExecutors() {
        ownedExecutors.forEach(ExecutorService::shutdownNow)
    }

    @Test
    fun `success and terminal drop become commit eligible without DLT`() {
        val dltCalls = AtomicInteger()
        val dltPublisher = DltPublisher { _, _, _ ->
            dltCalls.incrementAndGet()
            CompletableFuture.completedFuture(null)
        }
        val adapter = adapter(dltPublisher = dltPublisher)

        val success = adapter.deliver(subscription { _, _ -> completed(DeliveryOutcome.Success) }, record(), ownership())
        val terminal = adapter.deliver(
            subscription { _, _ -> completed(DeliveryOutcome.TerminalDrop("ENDPOINT_MISMATCH")) },
            record(offset = 8L),
            ownership(),
        )

        assertThat(success.toCompletableFuture()).isCompletedWithValue(DeliveryAction.Commit)
        assertThat(terminal.toCompletableFuture()).isCompletedWithValue(DeliveryAction.Commit)
        assertThat(dltCalls).hasValue(0)
    }

    @Test
    fun `invalid message waits for DLT success before commit`() {
        val dltCompletion = CompletableFuture<Void>()
        val adapter = adapter(dltPublisher = DltPublisher { _, _, _ -> dltCompletion })

        val delivery = adapter.deliver(
            subscription { _, _ -> completed(DeliveryOutcome.InvalidMessage("UNSUPPORTED_SCHEMA")) },
            record(),
            ownership(),
        ).toCompletableFuture()

        assertThat(delivery).isNotDone()
        dltCompletion.complete(null)
        assertThat(delivery).isCompletedWithValue(DeliveryAction.Commit)
    }

    @Test
    fun `DLT failure keeps record uncommitted and retries only sanitized DLT`() {
        val scheduler = ManualScheduler()
        val handlerCalls = AtomicInteger()
        val dltCalls = AtomicInteger()
        val adapter = adapter(
            scheduler = scheduler,
            dltPublisher = DltPublisher { _, _, _ ->
                if (dltCalls.incrementAndGet() == 1) {
                    CompletableFuture.failedFuture(IllegalStateException("broker unavailable"))
                } else {
                    CompletableFuture.completedFuture(null)
                }
            },
        )

        val delivery = adapter.deliver(
            subscription { _, _ ->
                handlerCalls.incrementAndGet()
                completed(DeliveryOutcome.InvalidMessage("BAD_JSON"))
            },
            record(),
            ownership(),
        ).toCompletableFuture()

        assertThat(delivery).isNotDone()
        assertThat(scheduler.pendingCount()).isEqualTo(1)
        scheduler.runNext()

        assertThat(delivery).isCompletedWithValue(DeliveryAction.Commit)
        assertThat(handlerCalls).hasValue(1)
        assertThat(dltCalls).hasValue(2)
    }

    @Test
    fun `retryable owns exactly three fixed technical retries then DLT`() {
        val scheduler = ManualScheduler()
        val attempts = mutableListOf<Int>()
        val dltReason = AtomicReference<String>()
        val adapter = adapter(
            scheduler = scheduler,
            dltPublisher = DltPublisher { _, reason, _ ->
                dltReason.set(reason)
                CompletableFuture.completedFuture(null)
            },
        )

        val delivery = adapter.deliver(
            subscription { _, context ->
                attempts += context.deliveryAttempt
                completed(DeliveryOutcome.Retryable(IllegalStateException("transient")))
            },
            record(),
            ownership(),
        ).toCompletableFuture()

        repeat(3) {
            assertThat(scheduler.nextDelay()).isEqualTo(Duration.ofSeconds(1))
            scheduler.runNext()
        }

        assertThat(attempts).containsExactly(1, 2, 3, 4)
        assertThat(dltReason).hasValue("RETRY_EXHAUSTED")
        assertThat(delivery).isCompletedWithValue(DeliveryAction.Commit)
    }

    @Test
    fun `backpressure reinvokes without consuming technical attempt`() {
        val scheduler = ManualScheduler()
        val attempts = mutableListOf<Int>()
        val calls = AtomicInteger()
        val adapter = adapter(scheduler = scheduler)

        val delivery = adapter.deliver(
            subscription { _, context ->
                attempts += context.deliveryAttempt
                if (calls.incrementAndGet() == 1) {
                    completed(DeliveryOutcome.Backpressure(Duration.ofMillis(250)))
                } else {
                    completed(DeliveryOutcome.Success)
                }
            },
            record(),
            ownership(),
        ).toCompletableFuture()

        assertThat(scheduler.nextDelay()).isEqualTo(Duration.ofMillis(250))
        scheduler.runNext()

        assertThat(attempts).containsExactly(1, 1)
        assertThat(delivery).isCompletedWithValue(DeliveryAction.Commit)
    }

    @Test
    fun `revoked ownership discards in-flight completion`() {
        val current = AtomicBoolean(true)
        val handlerCompletion = CompletableFuture<DeliveryOutcome>()
        val adapter = adapter()
        val delivery = adapter.deliver(
            subscription { _, _ -> handlerCompletion },
            record(),
            ownership(current),
        ).toCompletableFuture()

        current.set(false)
        handlerCompletion.complete(DeliveryOutcome.Success)

        assertThat(delivery).isCompletedWithValue(DeliveryAction.OwnershipLost)
    }

    @Test
    fun `handler starts on delivery executor even for immediate outcome`() {
        val callerThread = Thread.currentThread()
        val handlerThread = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "pipeline-delivery-test") }
        ownedExecutors += executor
        val adapter = adapter(deliveryExecutor = executor)

        val delivery = adapter.deliver(
            subscription { _, _ ->
                handlerThread.set(Thread.currentThread())
                completed(DeliveryOutcome.Success)
            },
            record(),
            ownership(),
        ).toCompletableFuture()

        await().until(delivery::isDone)
        assertThat(delivery.resultNow()).isEqualTo(DeliveryAction.Commit)
        assertThat(handlerThread.getPlain()).isNotSameAs(callerThread)
        assertThat(handlerThread.getPlain().name).isEqualTo("pipeline-delivery-test")
    }

    @Test
    fun `retry failure message never becomes a metric tag`() {
        val registry = SimpleMeterRegistry()
        val sentinel = "SECRET-SENTINEL-API-KEY"
        val scheduler = ManualScheduler()
        val adapter = adapter(
            scheduler = scheduler,
            metrics = DeliveryMetrics(registry),
        )

        adapter.deliver(
            subscription { _, _ -> completed(DeliveryOutcome.Retryable(IllegalStateException(sentinel))) },
            record(),
            ownership(),
        )

        val renderedMeters = registry.meters.joinToString(separator = "|") { meter -> meter.id.toString() }
        assertThat(renderedMeters).doesNotContain(sentinel)
    }

    private fun adapter(
        scheduler: ScheduledExecutorService = ManualScheduler(),
        dltPublisher: DltPublisher = DltPublisher { _, _, _ -> CompletableFuture.completedFuture(null) },
        deliveryExecutor: Executor = Executor(Runnable::run),
        metrics: DeliveryMetrics = DeliveryMetrics(SimpleMeterRegistry()),
    ): KafkaDeliveryAdapter = KafkaDeliveryAdapter(
        retryPolicy = DeliveryRetryPolicy(),
        dltPublisher = dltPublisher,
        dltRecordFactory = DltRecordFactory(),
        deliveryExecutor = deliveryExecutor,
        retryScheduler = scheduler,
        metrics = metrics,
    )

    private fun subscription(handler: DeliveryHandler): PipelineSubscription = PipelineSubscription(
        id = "calculator-normal",
        topics = listOf("source-topic"),
        groupId = "calculator-group",
        handler = handler,
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )

    private fun ownership(current: AtomicBoolean = AtomicBoolean(true)): PartitionOwnership = PartitionOwnership(
        listenerId = "calculator-normal",
        topicPartition = TopicPartition("source-topic", 0),
        generation = 1L,
        current = current::get,
    )

    private fun record(offset: Long = 7L): ConsumerRecord<String, String> = ConsumerRecord(
        "source-topic",
        0,
        offset,
        "key",
        "{}",
    )

    private fun completed(outcome: DeliveryOutcome): CompletableFuture<DeliveryOutcome> =
        CompletableFuture.completedFuture(outcome)
}

internal class ManualScheduler : AbstractExecutorService(), ScheduledExecutorService {
    private data class ScheduledTask(val delay: Duration, val command: Runnable)

    private val tasks = ArrayDeque<ScheduledTask>()
    private val shutdown = AtomicBoolean()

    fun pendingCount(): Int = tasks.size

    fun nextDelay(): Duration = tasks.first().delay

    fun runNext() {
        tasks.removeFirst().command.run()
    }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        tasks += ScheduledTask(Duration.ofNanos(unit.toNanos(delay)), command)
        return CompletedScheduledFuture
    }

    override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
        throw UnsupportedOperationException("callable scheduling is not used")

    override fun scheduleAtFixedRate(
        command: Runnable,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> = throw UnsupportedOperationException("fixed-rate scheduling is not used")

    override fun scheduleWithFixedDelay(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> = throw UnsupportedOperationException("fixed-delay scheduling is not used")

    override fun execute(command: Runnable) {
        tasks += ScheduledTask(Duration.ZERO, command)
    }

    override fun shutdown() {
        shutdown.set(true)
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown.set(true)
        val pending = tasks.map(ScheduledTask::command).toMutableList()
        tasks.clear()
        return pending
    }

    override fun isShutdown(): Boolean = shutdown.get()

    override fun isTerminated(): Boolean = shutdown.get() && tasks.isEmpty()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
}

private data object CompletedScheduledFuture : ScheduledFuture<Any?> {
    override fun getDelay(unit: TimeUnit): Long = 0L

    override fun compareTo(other: Delayed): Int = 0

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

    override fun isCancelled(): Boolean = false

    override fun isDone(): Boolean = true

    override fun get(): Any? = null

    override fun get(timeout: Long, unit: TimeUnit): Any? = null
}
