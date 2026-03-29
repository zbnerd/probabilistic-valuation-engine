package maple.expectation.infrastructure.worker

import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.ExpectationCalcMessage
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 기대값 계산 Worker - HIGH Priority (Issue #634)
 *
 * <h3>역할</h3>
 * <p>expectation_calc_high 큐에서 메시지를 소비하고 장비 기대값 계산 수행
 *
 * <h3>처리 흐름</h3>
 * <ol>
 *   <li>expectation_calc_high 큐에서 메시지 읽기</li>
 *   <li>ExpectationV4Port를 통해 비동기 계산 수행</li>
 *   <li>성공 시 아카이브, 실패 시 재시도 또는 삭제</li>
 * </ol>
 *
 * <h3>Feature Flag</h3>
 * <p>pgmq.worker.expectation-calc-high.enabled=true로 활성화
 *
 * @see ExpectationCalcMessage 메시지 페이로드
 * @see ExpectationV4Port 계산 포트
 */
@Component
@Profile("!test")
class ExpectationCalcWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    private val expectationPort: ExpectationV4Port,
) : PgmqWorker<ExpectationCalcMessage>(pgmqClient, executor, config) {

    override val queueName: String = QUEUE_NAME
    override val payloadClass: Class<ExpectationCalcMessage> = ExpectationCalcMessage::class.java
    override val workerSettings: PgmqWorkerConfig.WorkerSettings = config.expectationCalcHigh

    override fun process(message: PgmqMessage<ExpectationCalcMessage>): Boolean {
        val request = message.payload
        val context = TaskContext.of("ExpectationCalcWorker", "Process", request.userIgn)

        return executor.executeOrDefault({
            log.info("[ExpectationCalcWorker] Processing: userIgn={}", request.userIgn)

            val future = expectationPort.calculateExpectationAsync(
                request.userIgn,
                request.forceRecalculation,
            )
            future.join()

            log.info("[ExpectationCalcWorker] Completed: userIgn={}", request.userIgn)
            true
        }, false, context)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExpectationCalcWorker::class.java)

        /** 큐 이름 */
        const val QUEUE_NAME = "expectation_calc_high"
    }
}
