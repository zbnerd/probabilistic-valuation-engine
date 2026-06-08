package maple.cleanup.inbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment

class ConsumedChunkInboxTest {
    private val sampleEvent = """{"eventId":"e1","runId":"r1","endpoint":"basic","chunkId":"c1","objectKey":"k1","consumedAt":"2026-06-07T00:00:00Z"}"""
    private val mapper = ObjectMapper().registerModule(JavaTimeModule()).registerModule(kotlinModule())

    @Test
    fun `consume adds valid event to queue`() {
        val inbox = ConsumedChunkInbox(
            objectMapper = mapper,
            properties = InboxProperties(maxPending = 100),
        )
        val ack = org.mockito.kotlin.mock<Acknowledgment>()
        inbox.consume(sampleEvent, ack)
        assertEquals(1, inbox.size())
    }

    @Test
    fun `drain on empty queue returns empty list`() {
        val inbox = ConsumedChunkInbox(mapper, InboxProperties(maxPending = 100))
        assertEquals(emptyList(), inbox.drain())
    }

    @Test
    fun `consume skips malformed json and increments skipped counter`() {
        val inbox = ConsumedChunkInbox(mapper, InboxProperties(maxPending = 100))
        val ack = org.mockito.kotlin.mock<Acknowledgment>()
        inbox.consume("not-json", ack)
        assertEquals(0, inbox.size())
        assertEquals(1L, inbox.skipped())
    }

    @Test
    fun `drain returns all queued events and clears queue`() {
        val inbox = ConsumedChunkInbox(mapper, InboxProperties(maxPending = 100))
        val ack = org.mockito.kotlin.mock<Acknowledgment>()
        inbox.consume(sampleEvent, ack)
        inbox.consume(sampleEvent.replace("c1", "c2"), ack)
        val drained = inbox.drain()
        assertEquals(2, drained.size)
        assertEquals(0, inbox.size())
    }

    @Test
    fun `pending overflow drops oldest and counts drop`() {
        val inbox = ConsumedChunkInbox(mapper, InboxProperties(maxPending = 2))
        val ack = org.mockito.kotlin.mock<Acknowledgment>()
        repeat(3) { i -> inbox.consume(sampleEvent.replace("c1", "c$i"), ack) }
        assertEquals(2, inbox.size())
        assertEquals(1L, inbox.dropped())
    }

    @Test
    fun `autoStart false means consume is a no-op`() {
        val inbox = ConsumedChunkInbox(mapper, InboxProperties(maxPending = 100, autoStart = false))
        val ack = org.mockito.kotlin.mock<Acknowledgment>()
        inbox.consume(sampleEvent, ack)
        assertEquals(0, inbox.size())
    }
}
