package maple.synchronizer.consumer

import maple.expectation.common.event.ChunkExecutionIdentity
import maple.expectation.common.event.ChunkExecutionStatus
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.repository.ChunkExecutionClaim
import maple.synchronizer.repository.ChunkExecutionState
import maple.synchronizer.repository.ChunkExecutionRepository
import maple.synchronizer.repository.InsertChunkExecutionCommand
import org.slf4j.Logger
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore

@Component
class ChunkConsumerTemplate(
    private val logicExecutor: LogicExecutor,
    private val chunkExecutionRepository: ChunkExecutionRepository,
    private val metrics: SynchronizerMetrics,
    @Value("#{T(java.time.Duration).ofSeconds(\${chunk-execution.processing-timeout-seconds:600})}")
    private val processingTimeout: Duration = Duration.ofSeconds(600),
    @Value("#{T(java.time.Duration).ofSeconds(\${chunk-execution.retry.base-backoff-seconds:60})}")
    private val retryBaseBackoff: Duration = Duration.ofSeconds(60),
    @Value("\${chunk-execution.retry.max-attempts:5}")
    private val maxAttempts: Int = 5,
    @Value("\${chunk-execution.retry.artifact-missing-max-attempts:2}")
    private val artifactMissingMaxAttempts: Int = 2,
) {
    fun submit(request: ChunkConsumerRequest) {
        if (chunkExecutionRepository.insertPendingIfAbsent(request.toInsertCommand())) {
            metrics.recordChunkExecutionInserted(request.identity.executionType)
        }

        val state = chunkExecutionRepository.findExecutionState(request.identity)
        if (state == null) {
            request.log.warn(
                "[{}] chunk execution row missing after insert: runId={} chunkId={}",
                request.logPrefix,
                request.runId,
                request.chunkId,
            )
            return
        }

        if (state.shouldAckSkip()) {
            request.log.info(
                "[{}] skip chunk in terminal/current state: runId={} chunkId={} status={}",
                request.logPrefix,
                request.runId,
                request.chunkId,
                state.status,
            )
            metrics.recordChunkExecutionSkipped(request.identity.executionType, state.status)
            request.acknowledgment.acknowledge()
            return
        }

        if (!request.processingPermit.tryAcquire()) {
            request.log.info(
                "[{}] processing permit busy, will retry: runId={} chunkId={}",
                request.logPrefix,
                request.runId,
                request.chunkId,
            )
            return
        }

        val claim = chunkExecutionRepository.claimProcessing(request.identity, processingTimeout)
        if (claim == null) {
            request.processingPermit.release()
            if (state.shouldPreserveKafkaRedelivery()) {
                request.log.info(
                    "[{}] retryable chunk not due, leaving unacked for Kafka redelivery: runId={} chunkId={} nextRetryAt={}",
                    request.logPrefix,
                    request.runId,
                    request.chunkId,
                    state.nextRetryAt,
                )
                return
            }
            request.log.info(
                "[{}] skip - chunk not eligible for claim: runId={} chunkId={} status={}",
                request.logPrefix,
                request.runId,
                request.chunkId,
                state.status,
            )
            metrics.recordChunkExecutionSkipped(request.identity.executionType, state.status)
            request.acknowledgment.acknowledge()
            return
        }
        metrics.recordChunkExecutionClaimed(request.identity.executionType)
        if (state.status == ChunkExecutionStatus.PROCESSING) {
            metrics.recordChunkExecutionReclaimedExpired(request.identity.executionType)
        }

        request.onAccepted()

        request.executor.execute {
            logicExecutor.executeWithFinally(
                task = {
                    MDC.put("runId", request.runId)
                    MDC.put("chunkId", request.chunkId)
                    request.mdcValues.forEach { (key, value) -> MDC.put(key, value) }
                    processClaimed(request, claim)
                },
                finallyBlock = {
                    request.onFinally()
                    request.processingPermit.release()
                    MDC.clear()
                },
                context = request.lifecycleContext,
            )
        }
    }

    private fun processClaimed(
        request: ChunkConsumerRequest,
        claim: ChunkExecutionClaim,
    ) {
        if (request.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            markUnsupportedSchema(request, claim)
            return
        }

        logicExecutor.executeOrCatch(
            task = {
                request.process()
                markSucceededAndAck(request, claim)
            },
            recovery = { ex ->
                markFailureAndAck(request, claim, ex)
                null
            },
            context = request.processContext,
        )
    }

    private fun markSucceededAndAck(
        request: ChunkConsumerRequest,
        claim: ChunkExecutionClaim,
    ) {
        val marked = chunkExecutionRepository.markSucceeded(request.identity, claim.attemptCount)
        if (marked) {
            metrics.recordChunkExecutionSucceeded(request.identity.executionType)
            request.onSuccess()
            request.acknowledgment.acknowledge()
            return
        }

        request.log.warn(
            "[{}] success state write lost race, leaving unacked: runId={} chunkId={} attempt={}",
            request.logPrefix,
            request.runId,
            request.chunkId,
            claim.attemptCount,
        )
    }

    private fun markUnsupportedSchema(
        request: ChunkConsumerRequest,
        claim: ChunkExecutionClaim,
    ) {
        val message = "Unsupported schemaVersion=${request.schemaVersion}"
        val marked = chunkExecutionRepository.markFailedTerminal(
            request.identity,
            claim.attemptCount,
            message,
            UNSUPPORTED_SCHEMA_VERSION,
        )
        if (marked) {
            metrics.recordChunkExecutionFailed(
                request.identity.executionType,
                ChunkExecutionStatus.FAILED_TERMINAL,
                UNSUPPORTED_SCHEMA_VERSION,
            )
            request.log.warn(
                "[{}] terminal chunk failure: runId={} chunkId={} reason={}",
                request.logPrefix,
                request.runId,
                request.chunkId,
                UNSUPPORTED_SCHEMA_VERSION,
            )
            request.acknowledgment.acknowledge()
            return
        }

        logFailedStateWrite(request, claim)
    }

    private fun markFailureAndAck(
        request: ChunkConsumerRequest,
        claim: ChunkExecutionClaim,
        ex: Throwable,
    ) {
        val failure = classifyFailure(ex, claim)
        val error = ex.message ?: ex.javaClass.simpleName
        val marked = if (failure.terminalReason == null) {
            chunkExecutionRepository.markFailedRetryable(
                request.identity,
                claim.attemptCount,
                error,
                Instant.now().plus(retryBaseBackoff.multipliedBy(claim.attemptCount.toLong())),
            )
        } else {
            chunkExecutionRepository.markFailedTerminal(
                request.identity,
                claim.attemptCount,
                error,
                failure.terminalReason,
            )
        }

        if (marked) {
            val status = if (failure.terminalReason == null) {
                ChunkExecutionStatus.FAILED_RETRYABLE
            } else {
                ChunkExecutionStatus.FAILED_TERMINAL
            }
            metrics.recordChunkExecutionFailed(
                request.identity.executionType,
                status,
                failure.terminalReason ?: RETRYABLE_FAILURE,
            )
            request.onFailure(ex)
            if (failure.terminalReason == null) {
                request.log.warn(
                    "[{}] retryable chunk failure recorded, leaving unacked for Kafka redelivery: runId={} chunkId={} attempt={}",
                    request.logPrefix,
                    request.runId,
                    request.chunkId,
                    claim.attemptCount,
                )
                return
            }
            request.acknowledgment.acknowledge()
            return
        }

        logFailedStateWrite(request, claim)
    }

    private fun classifyFailure(
        ex: Throwable,
        claim: ChunkExecutionClaim,
    ): FailureDecision {
        val artifactMissing = ex.message?.contains("file not found", ignoreCase = true) == true
        if (artifactMissing && claim.attemptCount >= artifactMissingMaxAttempts) {
            return FailureDecision(ARTIFACT_MISSING_MAX_ATTEMPTS)
        }
        if (!artifactMissing && claim.attemptCount >= maxAttempts) {
            return FailureDecision(MAX_ATTEMPTS_EXCEEDED)
        }
        return FailureDecision(terminalReason = null)
    }

    private fun logFailedStateWrite(
        request: ChunkConsumerRequest,
        claim: ChunkExecutionClaim,
    ) {
        request.log.warn(
            "[{}] failure state write lost race, leaving unacked: runId={} chunkId={} attempt={}",
            request.logPrefix,
            request.runId,
            request.chunkId,
            claim.attemptCount,
        )
    }

    private fun ChunkExecutionStatus.isTerminalSkip(): Boolean =
        this == ChunkExecutionStatus.SUCCEEDED || this == ChunkExecutionStatus.FAILED_TERMINAL

    private fun ChunkExecutionState.shouldAckSkip(): Boolean {
        val now = Instant.now()
        if (status.isTerminalSkip()) {
            return true
        }
        if (status == ChunkExecutionStatus.FAILED_RETRYABLE && nextRetryAt?.isAfter(now) == true) {
            return false
        }
        if (status == ChunkExecutionStatus.PROCESSING && leaseUntil?.isAfter(now) == true) {
            return true
        }
        return false
    }

    private fun ChunkExecutionState.shouldPreserveKafkaRedelivery(): Boolean =
        status == ChunkExecutionStatus.FAILED_RETRYABLE && nextRetryAt?.isAfter(Instant.now()) == true

    private fun ChunkConsumerRequest.toInsertCommand(): InsertChunkExecutionCommand =
        InsertChunkExecutionCommand(
            identity = identity,
            topic = topic,
            messageKey = messageKey,
            eventType = eventType,
            schemaVersion = schemaVersion,
            eventPayloadJson = eventPayloadJson,
        )

    private data class FailureDecision(
        val terminalReason: String?,
    )

    private companion object {
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION"
        private const val ARTIFACT_MISSING_MAX_ATTEMPTS = "ARTIFACT_MISSING_MAX_ATTEMPTS"
        private const val MAX_ATTEMPTS_EXCEEDED = "MAX_ATTEMPTS_EXCEEDED"
        private const val RETRYABLE_FAILURE = "RETRYABLE_FAILURE"
    }
}

data class ChunkConsumerRequest(
    val logPrefix: String,
    val log: Logger,
    val identity: ChunkExecutionIdentity,
    val topic: String,
    val messageKey: String,
    val eventType: String,
    val schemaVersion: Int,
    val eventPayloadJson: String,
    val objectKey: String,
    val acknowledgment: Acknowledgment,
    val processingPermit: Semaphore,
    val executor: Executor,
    val processContext: TaskContext,
    val lifecycleContext: TaskContext,
    val mdcValues: Map<String, String> = emptyMap(),
    val process: () -> Unit,
    val onAccepted: () -> Unit = {},
    val onSuccess: () -> Unit = {},
    val onFailure: (Throwable) -> Unit = {},
    val onFinally: () -> Unit = {},
) {
    val runId: String = identity.runId
    val chunkId: String = identity.chunkId
}
