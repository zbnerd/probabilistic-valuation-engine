package maple.expectation.infrastructure.fanout

import jakarta.annotation.PreDestroy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import maple.expectation.core.port.out.FanOutQueuePort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Nexon FanOut Batch Loader (ADR-355)
 *
 * <h3>역할</h3>
 * <p>Batch Lane에서 수집된 OCID 목록을 병렬로 Nexon API 호출.
 * NexonRateLimiter로 동시성 제한, 429 발생 시 PGMQ에 재시도 메시지 enqueue.
 *
 * <h3>동시성 제어</h3>
 * <ul>
 *   <li>MetricsNexonApiClientWrapper: 중앙 집중 NexonRateLimiter (ADR-355, ADR-384)</li>
 *   <li>Virtual Thread: per-task virtual thread (ADR-355)</li>
 * </ul>
 *
 * <h3>429 처리</h3>
 * <p>429 Rate Limit 발생 시 FanOutQueuePort를 통해 PGMQ에 enqueue.
 * NexonFanOutWorker가 1~1.3초 jitter 후 재시도.
 *
 * @see FanOutQueuePort PGMQ 재시도 발행
 * @see NexonApiClient Nexon API 클라이언트
 */
@Component
class NexonFanOutBatchLoader(
    private val nexonApiClient: NexonApiClient,
    private val fanOutQueuePort: FanOutQueuePort,
    private val executor: LogicExecutor,
) {
    private val executorService: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * OCID 목록을 병렬로 조회
     *
     * @param ocids 조회할 OCID 목록
     * @return 성공한 OCID → EquipmentResponse 매핑 (429 건은 제외됨)
     */
    @PreDestroy
    fun shutdown() {
        executorService.shutdown()
        if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
            executorService.shutdownNow()
        }
    }

    fun load(ocids: List<String>): Map<String, EquipmentResponse> {
        if (ocids.isEmpty()) return emptyMap()

        val futures = ocids.map { ocid ->
            CompletableFuture.supplyAsync({
                fetchOrEnqueueRetry(ocid)
            }, executorService)
        }

        CompletableFuture.allOf(*futures.toTypedArray())
            .orTimeout(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .join()

        return futures.mapNotNull { it.join() }.associate { it }
    }

    /**
     * 단일 OCID 조회 또는 429 시 PGMQ enqueue
     *
     * @param ocid 캐릭터 OCID
     * @return 성공 시 OCID-Response 쌍, 실패 시 null
     */
    private fun fetchOrEnqueueRetry(ocid: String): Pair<String, EquipmentResponse>? {
        val context = TaskContext.of("FanOutBatchLoader", "Fetch", ocid)

        return executor.executeOrCatch(
            task = {
                val response = nexonApiClient.getItemDataByOcid(ocid)
                    .orTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join()
                ocid to response
            },
            recovery = { e ->
                if (is429(e)) {
                    log.warn("[FanOutBatchLoader] 429 Rate Limit, enqueuing retry: ocid={}", ocid)
                    fanOutQueuePort.enqueue(ocid, BATCH_LANE_USER)
                } else {
                    log.error("[FanOutBatchLoader] Failed: ocid={}", ocid, e)
                }
                null
            },
            context = context,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(NexonFanOutBatchLoader::class.java)

        private const val API_TIMEOUT_SECONDS = 10L
        private const val BATCH_TIMEOUT_SECONDS = 30L
        private const val BATCH_LANE_USER = "batch"

        /**
         * 429 Rate Limit 에러 여부 확인
         *
         * <p>CompletionException으로 래핑될 수 있으므로 cause 체인까지 확인
         */
        fun is429(throwable: Throwable): Boolean {
            var current: Throwable? = throwable
            while (current != null) {
                if (current is org.springframework.web.reactive.function.client.WebClientResponseException &&
                    current.statusCode.value() == 429
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }
}
