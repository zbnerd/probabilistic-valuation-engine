package maple.expectation.infrastructure.job

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CalculationResultData
import maple.expectation.core.port.out.CalculationResultPort
import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.NexonApiResponseEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CalculationExecutionService(
    private val jobPort: CalculationJobPort,
    private val eventAppender: DomainEventAppender,
    private val nexonApiResponseTopic: NexonApiResponseTopic,
    private val resultPort: CalculationResultPort,
    private val outboxPort: OutboxEventPort,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun startCalculation(jobId: UUID, workerId: String): Boolean {
        val locked = jobPort.lockForProcessing(jobId, workerId, CalculationJobStatus.SNAPSHOT_READY)
        if (locked) {
            jobPort.transitionStatus(jobId, CalculationJobStatus.SNAPSHOT_READY, CalculationJobStatus.CALCULATING)
            log.info("[jobId={}] Calculation started by {}", jobId, workerId)
        }
        return locked
    }

    @Transactional
    fun handleCalculationFailure(jobId: UUID, errorCode: String, errorMessage: String) {
        val job = jobPort.findJobById(jobId) ?: return

        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, errorCode, errorMessage)
            log.warn("[jobId={}] Calculation failed after {} retries: {}", jobId, job.retryCount, errorMessage)
            return
        }

        val backoffSeconds = calculateBackoff(job.retryCount)
        val nextRetry = Instant.now().plusSeconds(backoffSeconds)
        val retried = jobPort.retryCalculation(jobId, errorCode, nextRetry)
        if (retried) {
            val event = NexonApiResponseEventFactory.create(
                jobId.toString(),
                job.snapshotId?.toString() ?: return,
                "",
                job.ocid ?: return,
                job.userIgn,
                job.presetNo,
            )
            eventAppender.append(nexonApiResponseTopic, event)
            log.info("[jobId={}] Calculation retry scheduled (attempt {}, backoff={}s)", jobId, job.retryCount + 1, backoffSeconds)
        } else {
            jobPort.markFailed(jobId, errorCode, errorMessage)
        }
    }

    @Transactional
    fun completeCalculationWithResult(
        jobId: UUID,
        resultJson: String,
        characterClass: String,
        presetNo: Int,
        characterId: String,
    ): Boolean {
        val completed = jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED)
        if (!completed) return false

        val gzipData = gzipCompress(resultJson.toByteArray())
        val hash = sha256Hex(resultJson.toByteArray())

        resultPort.save(
            CalculationResultData(
                resultId = UUID.randomUUID(),
                jobId = jobId,
                characterClass = characterClass,
                presetNo = presetNo,
                schemaVersion = 1,
                contentType = "application/json",
                contentEncoding = "gzip",
                responseBody = gzipData,
                originalSize = resultJson.toByteArray().size,
                compressedSize = gzipData.size,
                hash = hash,
                status = "SUCCESS",
            ),
        )

        val eventPayload = objectMapper.writeValueAsString(
            mapOf(
                "jobId" to jobId.toString(),
                "characterId" to characterId,
                "presetNo" to presetNo,
                "contentEncoding" to "gzip",
                "schemaVersion" to 1,
            ),
        )
        outboxPort.insertIfAbsent("CALCULATION_COMPLETED", jobId, eventPayload)

        jobPort.unlock(jobId)
        log.info("[jobId={}] Calculation completed with result saved", jobId)
        return true
    }

    private fun calculateBackoff(retryCount: Int): Long {
        val baseSeconds = 30L
        return minOf(baseSeconds * (1L shl retryCount), 600L)
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
