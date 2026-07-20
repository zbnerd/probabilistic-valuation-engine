package maple.pipeline.messaging.contract

import maple.pipeline.messaging.dlt.DltRecordSanitizer

class PipelineSubscription(
    val id: String,
    topics: Collection<String>,
    val groupId: String,
    val concurrency: Int = 1,
    val handler: DeliveryHandler,
    val dltSanitizer: DltRecordSanitizer,
) {
    val topics: List<String> = java.util.List.copyOf(topics)

    init {
        require(id.isNotBlank() && groupId.isNotBlank() && topics.isNotEmpty())
        require(concurrency > 0)
    }
}
