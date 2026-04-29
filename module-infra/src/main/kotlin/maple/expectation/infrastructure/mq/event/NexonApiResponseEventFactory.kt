package maple.expectation.infrastructure.mq.event

import maple.expectation.core.domain.event.IntegrationEvent

object NexonApiResponseEventFactory {
    fun create(jobId: String, snapshotId: String, objectKey: String, characterId: String, userIgn: String, presetNo: Int): IntegrationEvent<Map<String, Any>> = IntegrationEvent.of(
        "NEXON_API_RESPONSE",
        mapOf<String, Any>(
            "jobId" to jobId,
            "snapshotId" to snapshotId,
            "objectKey" to objectKey,
            "characterId" to characterId,
            "userIgn" to userIgn,
            "presetNo" to presetNo,
        ),
    ).copy(schemaVersion = 1, jobId = jobId)
}
