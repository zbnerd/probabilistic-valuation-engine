package maple.expectation.infrastructure.messaging

import maple.expectation.core.port.out.CalculationDispatchPort
import maple.expectation.core.port.out.QueueNames
import maple.expectation.infrastructure.pgmq.CalculationRequestedPayload
import maple.expectation.infrastructure.pgmq.ExternalApiJobPayload
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["app.messaging.transport"], havingValue = "pgmq", matchIfMissing = true)
class PgmqCalculationDispatch(
    private val pgmqClient: PgmqClient,
) : CalculationDispatchPort {

    override fun dispatchExternalApiRequest(jobId: String, userIgn: String, presetNo: Int) {
        pgmqClient.send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(jobId, userIgn, presetNo))
    }

    override fun dispatchCalculationRequest(
        jobId: String,
        userIgn: String,
        presetNo: Int,
        characterId: String,
        characterClass: String,
        snapshotId: String,
    ) {
        pgmqClient.send(
            QueueNames.CALCULATION_REQUESTED,
            CalculationRequestedPayload(jobId, userIgn, presetNo, characterId, characterClass),
        )
    }
}
