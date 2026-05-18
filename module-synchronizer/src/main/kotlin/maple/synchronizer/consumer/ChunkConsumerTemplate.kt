package maple.synchronizer.consumer

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.Logger
import org.slf4j.MDC
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore

@Component
class ChunkConsumerTemplate(
    private val logicExecutor: LogicExecutor,
) {
    fun submit(request: ChunkConsumerRequest) {
        if (request.isAlreadySuccess()) {
            request.log.info(
                "[{}] skip already-successful chunk: runId={} chunkId={}",
                request.logPrefix,
                request.runId,
                request.chunkId,
            )
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

        if (!request.claimChunk()) {
            request.processingPermit.release()
            request.log.info(
                "[{}] skip - chunk already claimed: runId={} chunkId={}",
                request.logPrefix,
                request.runId,
                request.chunkId,
            )
            request.acknowledgment.acknowledge()
            return
        }

        request.onAccepted()
        MDC.put("runId", request.runId)
        MDC.put("chunkId", request.chunkId)
        request.mdcValues.forEach { (key, value) -> MDC.put(key, value) }

        request.executor.execute {
            logicExecutor.executeWithFinally(
                task = {
                    logicExecutor.executeOrCatch(
                        task = {
                            request.process()
                            request.markSuccess()
                            request.onSuccess()
                            request.acknowledgment.acknowledge()
                        },
                        recovery = { ex ->
                            request.markFailed(ex.message ?: "unknown")
                            request.onFailure(ex)
                            null
                        },
                        context = request.processContext,
                    )
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
}

data class ChunkConsumerRequest(
    val logPrefix: String,
    val log: Logger,
    val runId: String,
    val chunkId: String,
    val objectKey: String,
    val acknowledgment: Acknowledgment,
    val processingPermit: Semaphore,
    val executor: Executor,
    val processContext: TaskContext,
    val lifecycleContext: TaskContext,
    val mdcValues: Map<String, String> = emptyMap(),
    val isAlreadySuccess: () -> Boolean,
    val claimChunk: () -> Boolean,
    val process: () -> Unit,
    val markSuccess: () -> Unit,
    val markFailed: (String) -> Unit,
    val onAccepted: () -> Unit = {},
    val onSuccess: () -> Unit = {},
    val onFailure: (Throwable) -> Unit = {},
    val onFinally: () -> Unit = {},
)
