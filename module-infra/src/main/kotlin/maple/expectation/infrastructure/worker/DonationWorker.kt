package maple.expectation.infrastructure.worker

import maple.expectation.core.port.out.AlertPublisher
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.DonationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.queue.pgmq.DonationQueueProducer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 기부 알림 Worker (ADR-002)
 *
 * <h3>역할</h3>
 * <p>기부 큐에서 메시지를 소비하고 알림 발송 및 통계 업데이트
 *
 * <h3>처리 흐름</h3>
 * <ol>
 *   <li>donation_queue에서 메시지 읽기</li>
 *   <li>AlertPublisher를 통해 알림 발송</li>
 *   <li>성공 시 아카이브, 실패 시 재시도 또는 삭제</li>
 * </ol>
 *
 * <h3>Feature Flag</h3>
 * <p>pgmq.worker.donation.enabled=true로 활성화
 *
 * @see DonationQueueProducer 프로듀서
 * @see AlertPublisher 알림 발행
 */
@Component
@ConditionalOnProperty(name = ["pgmq.worker.donation.enabled"], havingValue = "true")
class DonationWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    private val alertPublisher: AlertPublisher,
) : PgmqWorker<DonationRequest>(pgmqClient, executor, config) {

    override val queueName: String = DonationQueueProducer.QUEUE_NAME
    override val payloadClass: Class<DonationRequest> = DonationRequest::class.java
    override val workerSettings: PgmqWorkerConfig.WorkerSettings = config.donation

    override fun process(message: PgmqMessage<DonationRequest>): Boolean {
        val request = message.payload
        val context = TaskContext.of("DonationWorker", "Process", "donation=${request.donationId}")

        return executor.executeOrDefault({
            log.info("🔄 [DonationWorker] Processing: donationId={}, userId={}, amount={}", request.donationId, request.userId, request.amount)

            // 알림 발송
            val messageText = buildDonationMessage(request)
            alertPublisher.sendInfo("☕ 새로운 후원", messageText)

            log.info("✅ [DonationWorker] Completed: donationId={}", request.donationId)
            true
        }, false, context)
    }

    private fun buildDonationMessage(request: DonationRequest): String {
        val messageBuilder = StringBuilder()
        messageBuilder.append("후원자 ID: ${request.userId}\n")
        messageBuilder.append("금액: ${request.amount}원\n")
        if (!request.message.isNullOrBlank()) {
            messageBuilder.append("메시지: ${request.message}")
        }
        return messageBuilder.toString()
    }

    companion object {
        private val log = LoggerFactory.getLogger(DonationWorker::class.java)
    }
}
