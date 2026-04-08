package maple.expectation.infrastructure.worker

import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.core.port.out.EquipmentFanOutPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.ExpectationCalcMessage
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 기대값 계산 Worker - LOW Priority (Issue #634)
 *
 * <h3>역할</h3>
 * <p>expectation_calc_low 큐에서 메시지를 소비하고 장비 기대값 계산 수행
 *
 * <h3>처리 흐름</h3>
 * <ol>
 *   <li>expectation_calc_low 큐에서 메시지 읽기</li>
 *   <li>ExpectationV4Port를 통해 비동기 계산 수행</li>
 *   <li>성공 시 아카이브, 실패 시 재시도 또는 삭제</li>
 * </ol>
 *
 * <h3>Feature Flag</h3>
 * <p>pgmq.worker.expectation-calc-low.enabled=true로 활성화
 *
 * @see ExpectationCalcMessage 메시지 페이로드
 * @see ExpectationV4Port 계산 포트
 */
@Component
@Profile("!test")
class ExpectationCalcLowWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    meterRegistry: MeterRegistry,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    private val expectationPort: ExpectationV4Port,
    private val characterOcidPort: CharacterOcidPort,
    private val equipmentFanOutPort: EquipmentFanOutPort,
) : PgmqWorker<ExpectationCalcMessage>(pgmqClient, executor, config, meterRegistry, lifecycleWrapper) {

    override val queueName: String = QUEUE_NAME
    override val payloadClass: Class<ExpectationCalcMessage> = ExpectationCalcMessage::class.java
    override val workerSettings: PgmqWorkerConfig.WorkerSettings = config.expectationCalcLow

    /**
     * 배치 pre-warm (ADR-700)
     *
     * <p>병렬 메시지 처리 전 배치 내 OCID 중복 제거 + 장비 캐시 pre-warm.
     * Best-effort: 실패해도 메시지 처리에 영향 없음.
     *
     * @see ExpectationCalcWorker.preWarmBatch 동일 로직 (HIGH priority)
     */
    override fun preWarmBatch(messages: List<PgmqMessage<ExpectationCalcMessage>>) {
        val context = TaskContext.of("ExpectationCalcLowWorker", "PreWarm", queueName)

        executor.executeVoid({
            // 1. 배치 내 unique IGN 추출
            val igns = messages.map { it.payload.userIgn }.toSet()

            // 2. Batch OCID resolve (재사용: CharacterOcidPort.resolveOcids)
            val ignToOcid = characterOcidPort.resolveOcids(igns)

            if (ignToOcid.isEmpty()) return@executeVoid

            // 3. Equipment cache pre-warm — CONCURRENT submission 필수
            //    Virtual Thread에서 동시 submit → semaphore(10) 초과 시 Batch Lane으로 routing
            //    → NexonFanOutBatchLoader.load()가 병렬 batch fetch → L1/L2 캐시 적재
            val warmupFutures = ignToOcid.values.map { ocid ->
                CompletableFuture.supplyAsync {
                    equipmentFanOutPort.preFetchByOcid(ocid)
                }
            }
            CompletableFuture.allOf(*warmupFutures.toTypedArray())
                .orTimeout(15, TimeUnit.SECONDS)
                .handle { _, _ -> }  // best-effort: 실패해도 진행

            log.info("[ExpectationCalcLowWorker] Pre-warm: {} igns → {} ocids", igns.size, ignToOcid.size)
        }, context)
    }

    override fun process(message: PgmqMessage<ExpectationCalcMessage>): Boolean {
        val request = message.payload
        val context = TaskContext.of("ExpectationCalcLowWorker", "Process", request.userIgn)

        return executor.executeOrDefault({
            log.info("[ExpectationCalcLowWorker] Processing: userIgn={}", request.userIgn)

            val future = expectationPort.calculateExpectationAsync(
                request.userIgn,
                request.forceRecalculation,
            )
            future.join()

            log.info("[ExpectationCalcLowWorker] Completed: userIgn={}", request.userIgn)
            true
        }, false, context)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExpectationCalcLowWorker::class.java)

        /** 큐 이름 */
        const val QUEUE_NAME = "expectation_calc_low"
    }
}
