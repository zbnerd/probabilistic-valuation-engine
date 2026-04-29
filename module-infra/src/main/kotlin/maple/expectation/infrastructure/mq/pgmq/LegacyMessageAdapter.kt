package maple.expectation.infrastructure.mq.pgmq

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import maple.expectation.core.domain.event.IntegrationEvent

class LegacyMessageAdapter(private val objectMapper: ObjectMapper) {

    fun adapt(rawPayload: Any, topicName: String): IntegrationEvent<*> {
        val tree: JsonNode = objectMapper.valueToTree(rawPayload)

        if (tree.has("eventId") && tree.has("eventType")) {
            return objectMapper.treeToValue(tree, IntegrationEvent::class.java)
        }

        return wrapLegacy(tree, topicName)
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrapLegacy(tree: com.fasterxml.jackson.databind.JsonNode, topicName: String): IntegrationEvent<Map<String, Any>> {
        val payload = objectMapper.treeToValue(tree, Map::class.java) as Map<String, Any>
        return IntegrationEvent(
            eventId = UUID.randomUUID().toString(),
            eventType = topicName.uppercase().replace("-", "_"),
            timestamp = Instant.now().toEpochMilli(),
            payload = payload,
            schemaVersion = 1,
            jobId = payload["jobId"]?.toString(),
        )
    }
}
