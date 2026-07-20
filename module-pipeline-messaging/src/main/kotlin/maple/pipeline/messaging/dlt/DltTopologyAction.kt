package maple.pipeline.messaging.dlt

import java.nio.charset.StandardCharsets

sealed interface DltTopologyAction {
    val topic: String
    val partitions: Int

    data class CreateDlt(
        override val topic: String,
        override val partitions: Int,
    ) : DltTopologyAction {
        init {
            requireTopologyTarget(topic, partitions)
        }
    }

    data class ExpandDlt(
        override val topic: String,
        override val partitions: Int,
    ) : DltTopologyAction {
        init {
            requireTopologyTarget(topic, partitions)
        }
    }
}

internal fun requireBoundedTopic(topic: String): String = topic.also {
    require(it.isNotBlank()) { "topic must not be blank" }
    require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_TOPIC_NAME_BYTES) {
        "topic exceeds $MAX_TOPIC_NAME_BYTES UTF-8 bytes"
    }
}

private fun requireTopologyTarget(topic: String, partitions: Int) {
    requireBoundedTopic(topic)
    require(partitions > 0) { "partition count must be positive" }
}

private const val MAX_TOPIC_NAME_BYTES = 249
