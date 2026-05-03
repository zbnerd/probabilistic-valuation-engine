package maple.expectation.adapter.outgoing

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotInputEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotInputRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class CalculationInputPortAdapter(
    private val repo: CalculationSnapshotInputRepository,
    private val objectMapper: ObjectMapper,
) : CalculationInputPort {

    @Transactional(value = "transactionManager", readOnly = false)
    override fun save(input: CalculationInput): CalculationInput {
        val payload = objectMapper.writeValueAsString(input)
        repo.save(
            CalculationSnapshotInputEntity(
                jobId = UUID.fromString(input.jobId),
                schemaVersion = input.schemaVersion,
                payload = payload,
            ),
        )
        return input
    }

    @Transactional(value = "transactionManager", readOnly = true)
    override fun findByJobId(jobId: UUID): CalculationInput? {
        val entity = repo.findByJobId(jobId) ?: return null
        return objectMapper.readValue(entity.payload, CalculationInput::class.java)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    override fun saveIfAbsent(input: CalculationInput): Boolean {
        val payload = objectMapper.writeValueAsString(input)
        return repo.insertIfAbsent(
            UUID.randomUUID(),
            UUID.fromString(input.jobId),
            input.schemaVersion,
            payload,
        ) > 0
    }
}
