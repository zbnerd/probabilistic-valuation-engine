package maple.synchronizer.consumer

import maple.expectation.common.event.ChunkExecutionIdentity
import maple.synchronizer.state.ChunkExecutionStateMachine
import maple.synchronizer.state.ChunkExecutionStatus
import maple.synchronizer.state.FailureDecision
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.synchronizer.metrics.ChunkExecutionMetrics
import maple.synchronizer.repository.ChunkExecutionClaim
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
    private val executionMetrics: ChunkExecutionMetrics,
    private val properties: ChunkExecutionProperties,
    private val stateMachine: ChunkExecutionStateMachine,
) {
    fun submit(request: ChunkConsumerRequest) {
        if (chunkExecutionRepository.insertPendingIfAbsent(request.toInsertCommand())) {
            executionMetrics.recordChunkExecutionInserted(request.identity.executionType)
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

        if (stateMachine.shouldAcknowledge(state)) {
            request.log.info(
                "[{}] skip chunk in terminal/current state: runId={} chunkId={} status={}",
                request.logPrefix,
                request.runId,
                request.chunkId,
                state.status,
            )
            executionMetrics.recordChunkExecutionSkipped(request.identity.executionType, state.status)
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
            if (stateMachine.shouldPreserveKafkaRedelivery(state)) {
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
            executionMetrics.recordChunkExecutionSkipped(request.identity.executionType, state.status)
            request.acknowledgment.acknowledge()
            return
        }
        executionMetrics.recordChunkExecutionClaimed(request.identity.executionType)
        if (stateMachine.isReclaimedExpired(state, Instant.now())) {
            executionMetrics.recordChunkExecutionReclaimedExpired(request.identity.executionType)
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
            executionMetrics.recordChunkExecutionSucceeded(request.identity.executionType)
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
            executionMetrics.recordChunkExecutionFailed(
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
        val decision = stateMachine.classifyFailure(ex, claim)
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
        executionMetrics.recordChunkExecutionFailed(
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

    private fun ChunkConsumerRequest.toInsertCommand(): InsertChunkExecutionCommand =
        InsertChunkExecutionCommand(
            identity = identity,
            topic = topic,
            messageKey = messageKey,
            eventType = eventType,
            schemaVersion = schemaVersion,
            eventPayloadJson = eventPayloadJson,
        )

    private companion object {
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION"
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
