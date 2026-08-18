package maple.cleanup.inbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import maple.pipeline.artifact.inbox.CleanupInboxEntry
import maple.pipeline.artifact.inbox.CleanupInboxStore
import maple.pipeline.artifact.inbox.InboxPutResult
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ConsumedChunkInboxTest {
    private val sampleEvent = """{"eventId":"e1","runId":"r1","endpoint":"basic","chunkId":"c1","objectKey":"k1","consumedAt":"2026-06-07T00:00:00Z"}"""
    private val mapper = ObjectMapper().registerModule(JavaTimeModule()).registerModule(kotlinModule())
    private val store = mock<CleanupInboxStore>()
    private val receivedAt = Instant.parse("2026-07-20T10:15:30Z")
    private val inbox = ConsumedChunkInbox(
        objectMapper = mapper,
        store = store,
        clock = Clock.fixed(receivedAt, ZoneOffset.UTC),
    )

    @Test
    fun `Created persists delivery metadata and returns Success`() {
        whenever(store.putIfAbsent(any())).thenReturn(CompletableFuture.completedFuture(InboxPutResult.Created))

        val outcome = inbox.consume(sampleEvent, context()).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.Success)
        val captor = argumentCaptor<CleanupInboxEntry>()
        verify(store).putIfAbsent(captor.capture())
        assertThat(captor.firstValue.eventId).isEqualTo("e1")
        assertThat(captor.firstValue.topic).isEqualTo("synchronizer.chunk.consumed")
        assertThat(captor.firstValue.partition).isEqualTo(4)
        assertThat(captor.firstValue.offset).isEqualTo(99)
        assertThat(captor.firstValue.receivedAt).isEqualTo(receivedAt)
        assertThat(captor.firstValue.event.objectKey).isEqualTo("k1")
    }

    @Test
    fun `Replay is idempotent Success`() {
        whenever(store.putIfAbsent(any())).thenReturn(CompletableFuture.completedFuture(InboxPutResult.Replay))

        val outcome = inbox.consume(sampleEvent, context()).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.Success)
    }

    @Test
    fun `event id integrity conflict is InvalidMessage`() {
        whenever(store.putIfAbsent(any())).thenReturn(
            CompletableFuture.completedFuture(InboxPutResult.IntegrityConflict("e1")),
        )

        val outcome = inbox.consume(sampleEvent, context()).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.InvalidMessage("INBOX_EVENT_ID_CONFLICT"))
    }

    @Test
    fun `storage failure is Retryable with original cause`() {
        val failure = IllegalStateException("storage unavailable")
        whenever(store.putIfAbsent(any())).thenReturn(CompletableFuture.failedFuture(failure))

        val outcome = inbox.consume(sampleEvent, context()).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.Retryable(failure))
    }

    @Test
    fun `malformed event is InvalidMessage without storage access`() {
        val outcome = inbox.consume("not-json", context()).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.InvalidMessage("INVALID_MESSAGE"))
        verify(store, never()).putIfAbsent(any())
    }

    private fun context(): DeliveryContext = DeliveryContext(
        listenerId = "cleanup-inbox",
        topic = "synchronizer.chunk.consumed",
        partition = 4,
        offset = 99,
        timestamp = Instant.EPOCH,
        key = "r1:basic:c1",
        deliveryAttempt = 1,
    )
}
