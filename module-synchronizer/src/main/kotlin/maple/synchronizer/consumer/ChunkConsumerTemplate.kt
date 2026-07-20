package maple.synchronizer.consumer

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import maple.expectation.common.event.ChunkExecutionIdentity
import maple.pipeline.messaging.contract.CompletionFailures
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.synchronizer.metrics.ChunkExecutionMetrics
import maple.synchronizer.repository.ChunkExecutionClaim
import maple.synchronizer.repository.ChunkExecutionRepository
import maple.synchronizer.repository.ChunkExecutionState
import maple.synchronizer.repository.InsertChunkExecutionCommand
import maple.synchronizer.state.ChunkExecutionStateMachine
import maple.synchronizer.state.ChunkExecutionStatus
import maple.synchronizer.state.FailureDecision
import org.springframework.stereotype.Component

@Component
class ChunkConsumerTemplate(
    private val chunkExecutionRepository: ChunkExecutionRepository,
    private val executionMetrics: ChunkExecutionMetrics,
    private val properties: ChunkExecutionProperties,
    private val stateMachine: ChunkExecutionStateMachine,
) {
    fun submit(request: ChunkConsumerRequest): CompletionStage<DeliveryOutcome> {
        if (!request.processingPermit.tryAcquire()) {
            return CompletableFuture.completedFuture(DeliveryOutcome.Backpressure(CAPACITY_BACKPRESSURE))
        }

        val preparation = runCatching {
            CompletableFuture.supplyAsync({ prepare(request) }, request.executor)
        }.getOrElse {
            request.processingPermit.release()
            return CompletableFuture.completedFuture(DeliveryOutcome.Backpressure(CAPACITY_BACKPRESSURE))
        }

        val pipeline = preparation.thenComposeAsync(
            { prepared ->
                when (prepared) {
                    is Preparation.Completed -> CompletableFuture.completedFuture(prepared.outcome)
                    is Preparation.Claimed -> processClaimed(request, prepared.claim)
                }
            },
            request.executor,
        ).handle { outcome, failure ->
            failure?.let { DeliveryOutcome.Retryable(CompletionFailures.unwrap(it)) } ?: outcome
        }

        pipeline.whenComplete { _, _ -> request.processingPermit.release() }
        return pipeline
    }

    private fun prepare(request: ChunkConsumerRequest): Preparation {
        if (chunkExecutionRepository.insertPendingIfAbsent(request.toInsertCommand())) {
            executionMetrics.recordChunkExecutionInserted(request.identity.executionType)
        }

        val state = chunkExecutionRepository.findExecutionState(request.identity)
        if (state == null) {
            return Preparation.Completed(
                DeliveryOutcome.Retryable(
                    IllegalStateException("chunk execution row missing after insert"),
                ),
            )
        }

        completedOutcome(state)?.let { outcome ->
            executionMetrics.recordChunkExecutionSkipped(request.identity.executionType, state.status)
            return Preparation.Completed(outcome)
        }

        futureRetryBackpressure(state)?.let { outcome ->
            return Preparation.Completed(outcome)
        }

        val claim = chunkExecutionRepository.claimProcessing(request.identity, properties.processingTimeout)
        if (claim == null) {
            return Preparation.Completed(DeliveryOutcome.Backpressure(CAPACITY_BACKPRESSURE))
        }
        executionMetrics.recordChunkExecutionClaimed(request.identity.executionType)
        if (stateMachine.isReclaimedExpired(state, Instant.now())) {
            executionMetrics.recordChunkExecutionReclaimedExpired(request.identity.executionType)
        }
        return Preparation.Claimed(claim)
    }

    private fun processClaimed(
        request: ChunkConsumerRequest,
        claim: ChunkExecutionClaim,
    ): CompletionStage<DeliveryOutcome> {
        if (request.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return CompletableFuture.completedFuture(markUnsupportedSchema(request, claim))
        }

        runCatching(request.process).exceptionOrNull()?.let { failure ->
            return CompletableFuture.completedFuture(markFailure(request, claim, failure))
        }

        val publication = runCatching(request.publishRequired).getOrElse { failure ->
            return CompletableFuture.completedFuture(markFailure(request, claim, failure))
        }

        return publication.handleAsync(
            { _, failure ->
                if (failure == null) {
                    markSucceeded(request, claim)
                } else {
                    markFailure(request, claim, CompletionFailures.unwrap(failure))
                }
            },
            request.executor,
        )
    }

    private fun markSucceeded(
        request: ChunkConsumerRequest,
        claim: ChunkExecutionClaim,
    ): DeliveryOutcome {
        val marked = chunkExecutionRepository.markSucceeded(request.identity, claim.attemptCount)
        if (marked) {
            executionMetrics.recordChunkExecutionSucceeded(request.identity.executionType)
            request.onObservedSuccess()
            return DeliveryOutcome.Success
        }
        return DeliveryOutcome.Retryable(
            IllegalStateException("success state write lost race"),
        )
    }

    private fun markUnsupportedSchema(
        request: ChunkConsumerRequest,
        claim: ChunkExecutionClaim,
    ): DeliveryOutcome {
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
            return DeliveryOutcome.InvalidMessage(UNSUPPORTED_SCHEMA_VERSION)
        }
        return DeliveryOutcome.Retryable(IllegalStateException("failure state write lost race"))
    }

    private fun markFailure(
        request: ChunkConsumerRequest,
        claim: ChunkExecutionClaim,
        ex: Throwable,
    ): DeliveryOutcome {
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
            return DeliveryOutcome.Retryable(IllegalStateException("failure state write lost race", ex))
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
        request.onObservedFailure(ex)

        return when (decision) {
            is FailureDecision.Retryable -> DeliveryOutcome.Retryable(ex)
            is FailureDecision.Terminal -> DeliveryOutcome.TerminalDrop(decision.terminalReason)
        }
    }

    private fun completedOutcome(state: ChunkExecutionState): DeliveryOutcome? = when (val status = state.status) {
        ChunkExecutionStatus.Succeeded -> DeliveryOutcome.Success
        is ChunkExecutionStatus.FailedTerminal -> DeliveryOutcome.TerminalDrop(
            status.reason ?: FAILED_TERMINAL,
        )
        ChunkExecutionStatus.Processing -> if (state.leaseUntil?.isAfter(Instant.now()) == true) {
            DeliveryOutcome.Success
        } else {
            null
        }
        is ChunkExecutionStatus.FailedRetryable,
        ChunkExecutionStatus.Pending,
        -> null
    }

    private fun futureRetryBackpressure(state: ChunkExecutionState): DeliveryOutcome.Backpressure? {
        val retryAt = state.nextRetryAt ?: return null
        val remaining = Duration.between(Instant.now(), retryAt)
        if (remaining.isNegative || remaining.isZero) return null
        val bounded = if (remaining > MAX_RETRY_BACKPRESSURE) MAX_RETRY_BACKPRESSURE else remaining
        return DeliveryOutcome.Backpressure(bounded)
    }

    private fun ChunkConsumerRequest.toInsertCommand(): InsertChunkExecutionCommand = InsertChunkExecutionCommand(
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
        private const val FAILED_TERMINAL = "FAILED_TERMINAL"
        private val CAPACITY_BACKPRESSURE: Duration = Duration.ofSeconds(1)
        private val MAX_RETRY_BACKPRESSURE: Duration = Duration.ofSeconds(30)
    }

    private sealed interface Preparation {
        data class Completed(val outcome: DeliveryOutcome) : Preparation
        data class Claimed(val claim: ChunkExecutionClaim) : Preparation
    }
}

data class ChunkConsumerRequest(
    val identity: ChunkExecutionIdentity,
    val topic: String,
    val messageKey: String,
    val eventType: String,
    val schemaVersion: Int,
    val eventPayloadJson: String,
    val processingPermit: Semaphore,
    val executor: Executor,
    val process: () -> Unit,
    val publishRequired: () -> CompletionStage<Void>,
    val onObservedSuccess: () -> Unit = {},
    val onObservedFailure: (Throwable) -> Unit = {},
)
