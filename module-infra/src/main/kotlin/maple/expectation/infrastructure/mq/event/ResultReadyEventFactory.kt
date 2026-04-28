package maple.expectation.infrastructure.mq.event

import maple.expectation.core.domain.event.IntegrationEvent

object ResultReadyEventFactory {
    fun create(
        jobId: String,
        resultId: String,
        characterId: String,
        presetNo: Int,
        contentEncoding: String = "gzip",
        schemaVersion: Int = 1
    ): IntegrationEvent<Map<String, Any>> {
        val payload: Map<String, Any> = mapOf(
            "jobId" to jobId,
            "resultId" to resultId,
            "characterId" to characterId,
            "presetNo" to presetNo,
            "contentEncoding" to contentEncoding,
            "schemaVersion" to schemaVersion
        )
        return IntegrationEvent.of("CALCULATION_COMPLETED", payload)
            .copy(schemaVersion = 1, jobId = jobId)
    }
}
