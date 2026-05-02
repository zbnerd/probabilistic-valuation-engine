package maple.expectation.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import maple.expectation.core.port.out.CalculationDispatchPort
import maple.expectation.core.port.out.KafkaTopicNames
import maple.expectation.infrastructure.persistence.repository.KafkaOutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["app.messaging.transport"], havingValue = "kafka")
class KafkaCalculationDispatch(
    private val outboxRepository: KafkaOutboxEventRepository,
    private val objectMapper: ObjectMapper,
) : CalculationDispatchPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun dispatchExternalApiRequest(jobId: String, userIgn: String, presetNo: Int) {
        val payload = mapOf(
            "schemaVersion" to 1,
            "jobId" to jobId,
            "requestKey" to buildRequestKey(userIgn, presetNo),
            "userIgn" to userIgn,
            "presetNo" to presetNo,
            "createdAt" to Instant.now().toString(),
        )

        outboxRepository.insertIfAbsent(
            id = UUID.randomUUID(),
            eventType = KafkaTopicNames.EXTERNAL_API_REQUESTED,
            aggregateId = UUID.fromString(jobId),
            aggregateType = "calculation_job",
            topic = KafkaTopicNames.EXTERNAL_API_REQUESTED,
            partitionKey = buildRequestKey(userIgn, presetNo),
            payload = objectMapper.writeValueAsString(payload),
        )

        log.debug("[KafkaDispatch] External API request enqueued: jobId={}", jobId)
    }

    override fun dispatchCalculationRequest(
        jobId: String,
        userIgn: String,
        presetNo: Int,
        characterId: String,
        characterClass: String,
        snapshotId: String,
    ) {
        val payload = mapOf(
            "schemaVersion" to 1,
            "jobId" to jobId,
            "requestKey" to buildRequestKey(userIgn, presetNo),
            "userIgn" to userIgn,
            "presetNo" to presetNo,
            "characterId" to characterId,
            "characterClass" to characterClass,
            "snapshotId" to snapshotId,
            "createdAt" to Instant.now().toString(),
        )

        outboxRepository.insertIfAbsent(
            id = UUID.randomUUID(),
            eventType = KafkaTopicNames.CALCULATION_REQUESTED,
            aggregateId = UUID.fromString(jobId),
            aggregateType = "calculation_job",
            topic = KafkaTopicNames.CALCULATION_REQUESTED,
            partitionKey = jobId,
            payload = objectMapper.writeValueAsString(payload),
        )

        log.debug("[KafkaDispatch] Calculation request enqueued: jobId={}", jobId)
    }

    private fun buildRequestKey(userIgn: String, presetNo: Int): String = "calc:v1:ign:${userIgn.lowercase()}:preset:$presetNo:schema:1"
}
