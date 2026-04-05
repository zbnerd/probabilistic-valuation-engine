package maple.expectation.infrastructure.pgmq

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/**
 * PGMQ Worker 추상 클래스 (ADR-002)
 *
 * <h3>역할</h3>
 * <p>PGMQ 큐에서 메시지를 소비하고 처리하는 Worker의 기본 구현
 *
 * <h3>처리 패턴</h3>
 * <ul>
 *   <li>성공: archive() 호출로 메시지 보관
 *   <li>재시도 가능 실패: 자동 재시도 (readCount < maxRetries)
 *   <li>최종 실패: delete() 호출로 DLQ 이동
 * </ul>
 *
 * <h3>Zero Try-Catch</h3>
 * <p>모든 예외 처리는 LogicExecutor에 위임 (Section 12 준수)
 *
 * @param T 메시지 페이로드 타입
 */
abstract class PgmqWorker<T : Any>(
    private val pgmqClient: PgmqClient,
    protected val executor: LogicExecutor,
    private val config: PgmqWorkerConfig,
    private val meterRegistry: MeterRegistry,
) {

    /** 메시지 병렬 처리용 Virtual Thread Executor */
    private val workerPool = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * 큐 이름
     */
    abstract val queueName: String

    /**
     * 메시지 페이로드 클래스
     */
    abstract val payloadClass: Class<T>

    /**
     * Worker별 설정
     */
    abstract val workerSettings: PgmqWorkerConfig.WorkerSettings

    /**
     * 메시지 처리 로직 (구현체에서 구현)
     *
     * @param message PGMQ 메시지
     * @return 처리 성공 여부 (true: archive, false: delete or retry)
     */
    protected abstract fun process(message: PgmqMessage<T>): Boolean

    /**
     * 메시지 처리 실패 시 후처리 훅 (선택적 오버라이드)
     *
     * <p>process()가 false를 반환하거나 예외 발생 시 호출.
     * 기본 구현은 no-op.
     * 429 Rate Limit 시 setVisibilityTimeout()으로 짧은 지연 설정에 사용.
     *
     * @param message 처리 실패한 메시지
     */
    protected open fun onProcessingFailed(message: PgmqMessage<T>) {
        // no-op by default
    }

    /**
     * 메시지 배치 처리
     *
     * <p>1. 큐에서 메시지 읽기
     * <p>2. 각 메시지 처리
     * <p>3. 성공 시 archive, 실패 시 delete
     */
    @Scheduled(fixedDelayString = "\${pgmq.worker.common.polling-interval-ms:300}")
    fun processMessages() {
        // Worker가 비활성화되어 있으면 스킵
        if (!workerSettings.enabled) {
            return
        }

        val context = TaskContext.of("PgmqWorker", "ProcessBatch", queueName)

        executor.executeVoid({
            val batchSize = workerSettings.batchSize ?: config.common.batchSize
            val visibilityTimeout = config.common.visibilityTimeoutSec

            val messages = pgmqClient.read(queueName, payloadClass, batchSize, visibilityTimeout)

            if (messages.isEmpty()) {
                return@executeVoid
            }

            log.debug("📥 [{}] Processing {} messages", queueName, messages.size)

            // ADR-355: Batch-level aggregate metrics
            val batchStart = System.nanoTime()
            var successCount = 0
            var failCount = 0

            val futures = messages.map { message ->
                CompletableFuture.supplyAsync({
                    processSingleMessage(message)
                }, workerPool)
            }
            CompletableFuture.allOf(*futures.toTypedArray()).join()
            futures.forEach { future ->
                if (future.get()) successCount++ else failCount++
            }

            val batchDuration = System.nanoTime() - batchStart
            meterRegistry.counter("pgmq.worker.processed", "queue", queueName, "status", "success")
                .increment(successCount.toDouble())
            meterRegistry.counter("pgmq.worker.processed", "queue", queueName, "status", "failed")
                .increment(failCount.toDouble())
            meterRegistry.timer("pgmq.worker.batch.latency", "queue", queueName)
                .record(batchDuration, TimeUnit.NANOSECONDS)
        }, context)
    }

    /**
     * 단일 메시지 처리
     *
     * <p>재시도 로직 포함:
     * <ul>
     *   <li>처리 성공 -> archive
     *   <li>처리 실패 + 재시도 가능 -> 재시도 (다음 poll에서 다시 읽힘)
     *   <li>처리 실패 + 재시도 불가 -> delete (DLQ)
     * </ul>
     */
    private fun processSingleMessage(message: PgmqMessage<T>): Boolean {
        val maxRetries = workerSettings.maxRetries ?: config.common.maxRetries
        val context = TaskContext.of("PgmqWorker", "ProcessMessage", "$queueName:${message.messageId}")

        val success = executor.executeOrDefault(
            { process(message) },
            false,
            context,
        )

        when {
            success -> {
                // 성공: 아카이브
                pgmqClient.archive(queueName, message.messageId)
                log.debug("✅ [{}] Archived message: msgId={}", queueName, message.messageId)
            }
            message.isRetryable(maxRetries) -> {
                // 재시도 가능: 후처리 훅 호출 후 다음 poll에서 다시 처리됨
                onProcessingFailed(message)
                log.warn("⚠️ [{}] Message will be retried: msgId={}, readCount={}", queueName, message.messageId, message.readCount)
            }
            else -> {
                // 최종 실패: 삭제 (DLQ)
                pgmqClient.delete(queueName, message.messageId)
                log.error("❌ [{}] Deleted message after max retries: msgId={}, readCount={}", queueName, message.messageId, message.readCount)
            }
        }

        return success
    }

    companion object {
        private val log = LoggerFactory.getLogger(PgmqWorker::class.java)
    }
}
