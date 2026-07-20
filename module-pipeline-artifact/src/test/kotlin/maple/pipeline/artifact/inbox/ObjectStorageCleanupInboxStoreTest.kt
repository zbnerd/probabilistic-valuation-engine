package maple.pipeline.artifact.inbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.event.ChunkConsumedEvent
import maple.pipeline.artifact.config.ArtifactStorageAutoConfiguration
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.CleanupInboxLayout
import maple.pipeline.artifact.storage.LocalFsObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.Bean

class ObjectStorageCleanupInboxStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val ownedExecutors = mutableListOf<ExecutorService>()

    @AfterEach
    fun closeOwnedExecutors() {
        ownedExecutors.forEach(ExecutorService::shutdownNow)
        ownedExecutors.clear()
    }

    @Test
    fun `concurrent same-event writes create once and retain the winning envelope`() {
        val store = newStore()
        val event = event("race-event")
        val firstEntry = entry(event, topic = "chunk-consumed-a", partition = 1, offset = 10L)
        val secondEntry = entry(
            event,
            topic = "chunk-consumed-b",
            partition = 2,
            offset = 20L,
            receivedAt = RECEIVED_AT.plusSeconds(30),
        )

        val firstWrite = store.putIfAbsent(firstEntry)
        val secondWrite = store.putIfAbsent(secondEntry)
        val firstResult = awaitSuccess(firstWrite)
        val secondResult = awaitSuccess(secondWrite)

        assertThat(listOf(firstResult, secondResult).filterIsInstance<InboxPutResult.Created>()).hasSize(1)
        assertThat(listOf(firstResult, secondResult).filterIsInstance<InboxPutResult.Replay>()).hasSize(1)
        val winningEntry = if (firstResult is InboxPutResult.Created) firstEntry else secondEntry
        assertThat(store.listPage(afterKey = null, limit = 10).entries.single().second).isEqualTo(winningEntry)
        assertThat(store.pendingCount()).isEqualTo(1L)
    }

    @Test
    fun `recreated store sees pending entry and classifies later delivery as replay`() {
        val originalStore = newStore()
        val event = event("restart-event")
        val original = entry(event, topic = "chunk-consumed", partition = 0, offset = 1L)
        assertThat(awaitSuccess(originalStore.putIfAbsent(original))).isEqualTo(InboxPutResult.Created)

        val recreatedStore = newStore()
        val redelivery = entry(
            event,
            topic = "chunk-consumed-retry",
            partition = 3,
            offset = 99L,
            receivedAt = RECEIVED_AT.plusSeconds(60),
        )

        assertThat(awaitSuccess(recreatedStore.putIfAbsent(redelivery))).isEqualTo(InboxPutResult.Replay)
        assertThat(recreatedStore.pendingCount()).isEqualTo(1L)
        assertThat(recreatedStore.listPage(afterKey = null, limit = 10).entries.single().second)
            .isEqualTo(original)
    }

    @Test
    fun `same event id with different semantic event conflicts without overwrite`() {
        val store = newStore()
        val event = event("conflict-event")
        val original = entry(event, topic = "chunk-consumed", partition = 0, offset = 1L)
        assertThat(awaitSuccess(store.putIfAbsent(original))).isEqualTo(InboxPutResult.Created)
        val conflicting = entry(
            event.copy(objectKey = "calculator/runs/run-1/result/chunks/conflicting.jsonl.gz"),
            topic = "chunk-consumed-retry",
            partition = 4,
            offset = 100L,
            receivedAt = RECEIVED_AT.plusSeconds(120),
        )

        assertThat(awaitSuccess(store.putIfAbsent(conflicting)))
            .isEqualTo(InboxPutResult.IntegrityConflict(event.eventId))
        assertThat(store.listPage(afterKey = null, limit = 10).entries.single().second).isEqualTo(original)
    }

    @Test
    fun `pagination is stable and delete is reflected by relist and pending count`() {
        val store = newStore()
        val eventIds = listOf("event-03", "event-01", "event-05", "event-02", "event-04")
        eventIds.forEach { eventId ->
            assertThat(awaitSuccess(store.putIfAbsent(entry(event(eventId)))))
                .isEqualTo(InboxPutResult.Created)
        }

        val first = store.listPage(afterKey = null, limit = 2)
        val second = store.listPage(afterKey = first.nextAfterKey, limit = 2)
        val third = store.listPage(afterKey = second.nextAfterKey, limit = 2)
        val listedKeys = (first.entries + second.entries + third.entries).map { it.first }
        val expectedKeys = eventIds.sorted().map(CleanupInboxLayout::entry)

        assertThat(first.nextAfterKey).isEqualTo(expectedKeys[1])
        assertThat(second.nextAfterKey).isEqualTo(expectedKeys[3])
        assertThat(third.nextAfterKey).isNull()
        assertThat(listedKeys).containsExactlyElementsOf(expectedKeys).doesNotHaveDuplicates()
        assertThat(store.pendingCount()).isEqualTo(5L)

        store.delete(expectedKeys[2])

        assertThat(store.listPage(afterKey = null, limit = 10).entries.map { it.first })
            .containsExactlyElementsOf(expectedKeys.filterNot { it == expectedKeys[2] })
        assertThat(store.pendingCount()).isEqualTo(4L)
    }

    @Test
    fun `invalid event ids are rejected before object storage access`() {
        val store = newStore()

        listOf("", "bad/event").forEach { invalidEventId ->
            assertThatThrownBy { store.putIfAbsent(entry(event(invalidEventId))) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        val storedFileCount = Files.walk(tempDir).use { paths -> paths.filter(Files::isRegularFile).count() }
        assertThat(storedFileCount).isZero()
    }

    @Test
    fun `auto configuration declares exactly one cleanup inbox store bean`() {
        val storeBeans = ArtifactStorageAutoConfiguration::class.java.declaredMethods.filter { method ->
            method.returnType == CleanupInboxStore::class.java && method.getAnnotation(Bean::class.java) != null
        }

        assertThat(storeBeans).hasSize(1)
    }

    private fun newStore(): CleanupInboxStore = ObjectStorageCleanupInboxStore(
        objectStorage = LocalFsObjectStorage(tempDir.toString(), newExecutor(), meterRegistry = null),
        objectMapper = objectMapper,
    )

    private fun newExecutor(): ExecutorService =
        Executors.newVirtualThreadPerTaskExecutor().also(ownedExecutors::add)

    private fun event(eventId: String): ChunkConsumedEvent = ChunkConsumedEvent(
        eventId = eventId,
        runId = "run-1",
        endpoint = "result",
        chunkId = "chunk-1",
        objectKey = "calculator/runs/run-1/result/chunks/chunk-1.jsonl.gz",
        sourceObjectKey = "runs/run-1/item-equipment/chunks/chunk-1.jsonl.gz",
        consumedAt = CONSUMED_AT,
    )

    private fun entry(
        event: ChunkConsumedEvent,
        topic: String = "chunk-consumed",
        partition: Int = 0,
        offset: Long = 1L,
        receivedAt: Instant = RECEIVED_AT,
    ): CleanupInboxEntry = CleanupInboxEntry(
        eventId = event.eventId,
        topic = topic,
        partition = partition,
        offset = offset,
        receivedAt = receivedAt,
        event = event,
    )

    private fun <T> awaitSuccess(stage: CompletionStage<T>): T {
        val observed = AtomicReference<Completion<T>?>()
        stage.whenComplete { value, failure -> observed.setRelease(Completion(value, failure)) }
        await().atMost(Duration.ofSeconds(5)).until { observed.getAcquire() != null }
        val completion = requireNotNull(observed.getAcquire())
        assertThat(completion.failure).isNull()
        return requireNotNull(completion.value)
    }

    private data class Completion<T>(val value: T?, val failure: Throwable?)

    private companion object {
        val RECEIVED_AT: Instant = Instant.parse("2026-07-20T10:00:00Z")
        val CONSUMED_AT: Instant = Instant.parse("2026-07-20T09:59:00Z")
    }
}
