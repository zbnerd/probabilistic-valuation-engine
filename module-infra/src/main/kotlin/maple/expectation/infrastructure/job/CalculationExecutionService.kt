package maple.expectation.infrastructure.job

import java.time.Instant
import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CalculationResultData
import maple.expectation.core.port.out.CalculationResultPort
import maple.expectation.core.port.out.QueueNames
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.NexonApiResponseEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CalculationExecutionService(
    private val jobPort: CalculationJobPort,
    private val eventAppender: DomainEventAppender,
    private val nexonApiResponseTopic: NexonApiResponseTopic,
    private val resultPort: CalculationResultPort,
    private val pgmqClient: PgmqClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(value = "transactionManager", readOnly = false)
    fun startCalculation(jobId: UUID, workerId: String): Boolean {
        val locked = jobPort.lockForProcessing(jobId, workerId, CalculationJobStatus.SNAPSHOT_READY)
        if (locked) {
            jobPort.transitionStatus(jobId, CalculationJobStatus.SNAPSHOT_READY, CalculationJobStatus.CALCULATING)
            log.info("[jobId={}] Calculation started by {}", jobId, workerId)
        }
        return locked
    }

    /**
     * Optimized complete: single UPDATE (SNAPSHOT_READY → COMPLETED) + INSERT ON CONFLICT.
     * No CPU work inside transaction — gzip/hash must be pre-computed by caller.
     */
    @Transactional(value = "transactionManager", readOnly = false)
    fun completeCalculation(
        jobId: UUID,
        gzipData: ByteArray,
        hash: String,
        originalSize: Int,
        compressedSize: Int,
        characterClass: String,
        presetNo: Int,
        characterId: String,
        totalExpectedCost: Long? = null,
        maxPresetNo: Int? = null,
        presetsJson: String? = null,
    ): Boolean {
        val completed = jobPort.completeFromSnapshotReady(jobId)
        if (!completed) return false

        resultPort.saveIfAbsent(
            CalculationResultData(
                resultId = UUID.randomUUID(),
                jobId = jobId,
                characterClass = characterClass,
                presetNo = presetNo,
                schemaVersion = 1,
                contentType = "application/json",
                contentEncoding = "gzip",
                responseBody = gzipData,
                originalSize = originalSize,
                compressedSize = compressedSize,
                hash = hash,
                status = "SUCCESS",
                totalExpectedCost = totalExpectedCost,
                maxPresetNo = maxPresetNo,
                presetsJson = presetsJson,
            ),
        )

        pgmqClient.send(
            QueueNames.RESULT_READY,
            mapOf(
                "jobId" to jobId.toString(),
                "characterId" to characterId,
                "presetNo" to presetNo,
                "contentEncoding" to "gzip",
                "schemaVersion" to 1,
            ),
        )

        log.info("[jobId={}] Calculation completed (optimized single-TX)", jobId)
        return true
    }

    /**
     * Split-pipeline complete: single UPDATE (CALCULATING → COMPLETED) + INSERT ON CONFLICT.
     * No CPU work inside transaction — gzip/hash must be pre-computed by CalculationWorker.
     */
    @Transactional(value = "transactionManager", readOnly = false)
    fun completeCalculatedResult(
        jobId: UUID,
        gzipData: ByteArray,
        hash: String,
        originalSize: Int,
        compressedSize: Int,
        characterClass: String,
        presetNo: Int,
        characterId: String,
        totalExpectedCost: Long? = null,
        maxPresetNo: Int? = null,
        presetsJson: String? = null,
    ): Boolean {
        val completed = jobPort.completeFromCalculating(jobId)
        if (!completed) return false

        resultPort.saveIfAbsent(
            CalculationResultData(
                resultId = UUID.randomUUID(),
                jobId = jobId,
                characterClass = characterClass,
                presetNo = presetNo,
                schemaVersion = 1,
                contentType = "application/json",
                contentEncoding = "gzip",
                responseBody = gzipData,
                originalSize = originalSize,
                compressedSize = compressedSize,
                hash = hash,
                status = "SUCCESS",
                totalExpectedCost = totalExpectedCost,
                maxPresetNo = maxPresetNo,
                presetsJson = presetsJson,
            ),
        )

        pgmqClient.send(
            QueueNames.RESULT_READY,
            mapOf(
                "jobId" to jobId.toString(),
                "characterId" to characterId,
                "presetNo" to presetNo,
                "contentEncoding" to "gzip",
                "schemaVersion" to 1,
            ),
        )

        log.info("[jobId={}] Calculation completed from split pipeline", jobId)
        return true
    }

    fun startAndCompleteCalculation(
        jobId: UUID,
        workerId: String,
        resultJson: String,
        characterClass: String,
        presetNo: Int,
        characterId: String,
    ): Boolean {
        // CPU work outside TX boundary (gzip + SHA-256)
        val rawBytes = resultJson.toByteArray()
        val gzipData = gzipCompress(rawBytes)
        val hash = sha256Hex(rawBytes)
        return startAndCompleteCalculationInTx(
            jobId = jobId,
            workerId = workerId,
            characterClass = characterClass,
            presetNo = presetNo,
            characterId = characterId,
            gzipData = gzipData,
            hash = hash,
            originalSize = rawBytes.size,
            compressedSize = gzipData.size,
        )
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun startAndCompleteCalculationInTx(
        jobId: UUID,
        workerId: String,
        characterClass: String,
        presetNo: Int,
        characterId: String,
        gzipData: ByteArray,
        hash: String,
        originalSize: Int,
        compressedSize: Int,
    ): Boolean {
        val locked = jobPort.lockForProcessing(jobId, workerId, CalculationJobStatus.SNAPSHOT_READY)
        if (!locked) return false
        jobPort.transitionStatus(jobId, CalculationJobStatus.SNAPSHOT_READY, CalculationJobStatus.CALCULATING)

        val completed = jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED)
        if (!completed) return false

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
                originalSize = originalSize,
                compressedSize = compressedSize,
                hash = hash,
                status = "SUCCESS",
            ),
        )

        pgmqClient.send(
            QueueNames.RESULT_READY,
            mapOf(
                "jobId" to jobId.toString(),
                "characterId" to characterId,
                "presetNo" to presetNo,
                "contentEncoding" to "gzip",
                "schemaVersion" to 1,
            ),
        )

        jobPort.unlock(jobId)
        log.info("[jobId={}] Calculation completed with result saved", jobId)
        return true
    }

    @Transactional(value = "transactionManager", readOnly = false)
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

    fun completeCalculationWithResult(
        jobId: UUID,
        resultJson: String,
        characterClass: String,
        presetNo: Int,
        characterId: String,
    ): Boolean {
        // CPU work outside TX boundary (gzip + SHA-256)
        val rawBytes = resultJson.toByteArray()
        val gzipData = gzipCompress(rawBytes)
        val hash = sha256Hex(rawBytes)
        return completeCalculationWithResultInTx(
            jobId = jobId,
            characterClass = characterClass,
            presetNo = presetNo,
            characterId = characterId,
            gzipData = gzipData,
            hash = hash,
            originalSize = rawBytes.size,
            compressedSize = gzipData.size,
        )
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun completeCalculationWithResultInTx(
        jobId: UUID,
        characterClass: String,
        presetNo: Int,
        characterId: String,
        gzipData: ByteArray,
        hash: String,
        originalSize: Int,
        compressedSize: Int,
    ): Boolean {
        val completed = jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED)
        if (!completed) return false

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
                originalSize = originalSize,
                compressedSize = compressedSize,
                hash = hash,
                status = "SUCCESS",
            ),
        )

        pgmqClient.send(
            QueueNames.RESULT_READY,
            mapOf(
                "jobId" to jobId.toString(),
                "characterId" to characterId,
                "presetNo" to presetNo,
                "contentEncoding" to "gzip",
                "schemaVersion" to 1,
            ),
        )

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
