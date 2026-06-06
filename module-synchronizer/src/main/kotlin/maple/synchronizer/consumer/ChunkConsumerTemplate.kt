package maple.synchronizer.consumer

import maple.expectation.common.event.ChunkExecutionIdentity
import maple.expectation.error.exception.ArtifactNotFoundException
import maple.synchronizer.state.ChunkExecutionStatus
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.repository.ChunkExecutionClaim
import maple.synchronizer.repository.ChunkExecutionState
import maple.synchronizer.repository.ChunkExecutionRepository
import maple.synchronizer.repository.InsertChunkExecutionCommand
import org.slf4j.Logger
import org.slf4j.MDC
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore

@Component
class ChunkConsumerTemplate(
    private val logicExecutor: LogicExecutor,
    private val chunkExecutionRepository: ChunkExecutionRepository,
    private val metrics: SynchronizerMetrics,
    private val properties: ChunkExecutionProperties,
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

        val claim = chunkExecutionRepository.claimProcessing(request.identity, properties.processingTimeout)
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
        if (state.isReclaimedExpired(Instant.now())) {
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
                ChunkExecutionStatus.FailedTerminal(UNSUPPORTED_SCHEMA_VERSION),
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
        val decision = classifyFailure(ex, claim)
        val error = ex.message ?: ex.javaClass.simpleName

        val marked = when (decision) {
            is FailureDecision.Retryable -> chunkExecutionRepository.markFailedRetryable(
                request.identity,
                claim.attemptCount,
                error,
                decision.nextRetryAt,
            )
            is FailureDecision.Terminal -> chunkExecutionRepository.markFailedTerminal(
                request.identity,
                claim.attemptCount,
                error,
                decision.terminalReason,
            )
        }

        if (!marked) {
            logFailedStateWrite(request, claim)
            return
        }

        val status: ChunkExecutionStatus = when (decision) {
            is FailureDecision.Retryable -> ChunkExecutionStatus.FailedRetryable(decision.nextRetryAt)
            is FailureDecision.Terminal -> ChunkExecutionStatus.FailedTerminal(decision.terminalReason)
        }
        metrics.recordChunkExecutionFailed(
            request.identity.executionType,
            status,
            decision.reason,
        )
        request.onFailure(ex)

        when (decision) {
            is FailureDecision.Retryable -> {
                request.log.warn(
                    "[{}] retryable chunk failure recorded, leaving unacked for Kafka redelivery: runId={} chunkId={} attempt={}",
                    request.logPrefix,
                    request.runId,
                    request.chunkId,
                    claim.attemptCount,
                )
            }
            is FailureDecision.Terminal -> {
                request.acknowledgment.acknowledge()
            }
        }
    }

    private fun classifyFailure(
        ex: Throwable,
        claim: ChunkExecutionClaim,
    ): FailureDecision {
        val artifactMissing = ex is ArtifactNotFoundException
        val maxAttempts = if (artifactMissing) {
            properties.retry.artifactMissingMaxAttempts
        } else {
            properties.retry.maxAttempts
        }
        return if (claim.attemptCount >= maxAttempts) {
            val terminalReason = if (artifactMissing) ARTIFACT_MISSING_MAX_ATTEMPTS else MAX_ATTEMPTS_EXCEEDED
            FailureDecision.Terminal(
                attemptCount = claim.attemptCount,
                terminalReason = terminalReason,
            )
        } else {
            FailureDecision.Retryable(
                attemptCount = claim.attemptCount,
                nextRetryAt = Instant.now().plus(
                    properties.retryBaseBackoff.multipliedBy(claim.attemptCount.toLong()),
                ),
            )
        }
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

    private fun ChunkExecutionState.isReclaimedExpired(now: Instant): Boolean =
        (status as? ChunkExecutionStatus.Processing)?.isReclaimed(leaseUntil, now) == true

    private fun ChunkExecutionState.shouldAckSkip(): Boolean {
        val now = Instant.now()
        // Note: PROCESSING + active lease returns TRUE (skip — another worker holds it).
        // FAILED_RETRYABLE + future retry returns FALSE (don't skip — Kafka should redeliver later).
        // This inversion is intentional: skip means "ack and move on", so we ack when the work
        // is already done (terminal / leased) and leave unacked when Kafka should retry.
        return when (val s = status) {
            is ChunkExecutionStatus.Succeeded,
            is ChunkExecutionStatus.FailedTerminal -> true
            is ChunkExecutionStatus.FailedRetryable -> s.nextRetryAt?.isAfter(now) != true
            ChunkExecutionStatus.Processing -> leaseUntil?.isAfter(now) == true
            ChunkExecutionStatus.Pending -> false
        }
    }

    private fun ChunkExecutionState.shouldPreserveKafkaRedelivery(): Boolean {
        val s = status as? ChunkExecutionStatus.FailedRetryable ?: return false
        return s.nextRetryAt?.isAfter(Instant.now()) == true
    }

    private fun ChunkConsumerRequest.toInsertCommand(): InsertChunkExecutionCommand =
        InsertChunkExecutionCommand(
            identity = identity,
            topic = topic,
            messageKey = messageKey,
            eventType = eventType,
            schemaVersion = schemaVersion,
            eventPayloadJson = eventPayloadJson,
        )

    private sealed class FailureDecision {
        abstract val attemptCount: Int
        abstract val reason: String

        data class Retryable(
            override val attemptCount: Int,
            val nextRetryAt: Instant,
        ) : FailureDecision() {
            override val reason: String = RETRYABLE_FAILURE
        }

        data class Terminal(
            override val attemptCount: Int,
            val terminalReason: String,
        ) : FailureDecision() {
            override val reason: String = terminalReason
        }
    }

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
