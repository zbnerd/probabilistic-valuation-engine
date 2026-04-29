package maple.expectation.infrastructure.mq.event

import maple.expectation.core.domain.event.IntegrationEvent

object NexonApiRequestEventFactory {
    fun create(jobId: String, ocid: String, userIgn: String, presetNo: Int, eventType: String = "FETCH_EQUIPMENT"): IntegrationEvent<Map<String, Any>> = IntegrationEvent.of(
        "NEXON_API_REQUEST",
        mapOf<String, Any>(
            "jobId" to jobId,
            "ocid" to ocid,
            "userIgn" to userIgn,
            "presetNo" to presetNo,
            "eventType" to eventType,
        ),
    ).copy(schemaVersion = 1, jobId = jobId)
}
