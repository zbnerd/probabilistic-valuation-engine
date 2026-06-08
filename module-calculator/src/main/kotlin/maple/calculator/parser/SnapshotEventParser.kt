package maple.calculator.parser

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.springframework.stereotype.Component

/**
 * Parses the Kafka envelope (`SnapshotChunkReadyEvent`) for the snapshot
 * chunk consumer. The consumer does not import `ObjectMapper`; all JSON
 * access lives here.
 */
@Component
class SnapshotEventParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(message: String): SnapshotChunkReadyEvent = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
}
