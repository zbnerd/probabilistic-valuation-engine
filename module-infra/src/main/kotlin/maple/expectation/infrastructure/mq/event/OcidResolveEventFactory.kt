package maple.expectation.infrastructure.mq.event

import maple.expectation.core.domain.event.IntegrationEvent

object OcidResolveEventFactory {
    fun create(jobId: String, userIgn: String, presetNo: Int): IntegrationEvent<Map<String, Any>> = IntegrationEvent.of(
        "OCID_RESOLVE",
        mapOf<String, Any>(
            "jobId" to jobId,
            "userIgn" to userIgn,
            "presetNo" to presetNo,
        ),
    ).copy(schemaVersion = 1, jobId = jobId)
}
