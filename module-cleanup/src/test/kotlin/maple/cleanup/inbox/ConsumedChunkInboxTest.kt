package maple.cleanup.inbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment
import kotlin.test.assertEquals

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
}
