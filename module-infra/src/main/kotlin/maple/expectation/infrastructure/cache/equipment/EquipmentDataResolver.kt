package maple.expectation.infrastructure.cache.equipment

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.worker.EquipmentDbWorker
import maple.expectation.infrastructure.provider.EquipmentDataProvider
import maple.expectation.infrastructure.util.AsyncUtils
import maple.expectation.util.GzipUtils
import maple.expectation.util.StringMaskingUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * 장비 데이터 확보 우선순위 처리기 (Issue #158: EquipmentResponse 캐싱 제거)
 *
 * 데이터 소스 우선순위:
 * 1. DB JSON (15분 TTL) - 우선
 * 2. Nexon API - DB 없거나 만료 시 (비동기)
 *
 * Issue #158 핵심 변경:
 * - Expectation 경로에서 EquipmentResponse 캐싱 완전 제거
 * - L1/L2 캐시 저장 금지
 * - 최종 결과인 TotalExpectationResponse만 캐싱
 * - EquipmentResponse(270KB+)가 Redis에 저장되지 않음
 * - Nexon API 호출 후 DB 저장 → 다음 요청에서 API 호출 최소화
 *
 * @see TotalExpectationCacheService
 * @see EquipmentDataProvider
 * @see EquipmentDbWorker
 */
@Component
class EquipmentDataResolver(
    private val dataProvider: EquipmentDataProvider,
    private val dbWorker: EquipmentDbWorker,
    @Qualifier("expectationComputeIoExecutor") private val expectationExecutor: Executor,
    private val executor: LogicExecutor,
    meterRegistry: MeterRegistry,
) {
    private val dbSaveFailCounter: Counter = Counter.builder("equipment.data.db.save.fail")
        .description("Equipment DB 비동기 저장 실패 횟수 (fire-and-forget)")
        .register(meterRegistry)

    /**
     * 장비 데이터 비동기 확보 (DB → API 우선순위)
     */
    fun resolveAsync(ocid: String, userIgn: String): CompletableFuture<ByteArray> = executor.executeOrDefault(
        { resolveAsyncInternal(ocid, userIgn) },
        CompletableFuture.failedFuture(
            IllegalStateException(
                "[DataResolver] Resolve failed for ocid=${StringMaskingUtils.maskOcid(ocid)}",
            ),
        ),
        TaskContext.of("DataResolver", "ResolveAsync", StringMaskingUtils.maskOcid(ocid)),
    )

    private fun resolveAsyncInternal(ocid: String, userIgn: String): CompletableFuture<ByteArray> = dbWorker.findValidJson(ocid)
        .map { json ->
            log.debug("[DataResolver] DB HIT for userIgn={}", userIgn)
            CompletableFuture.completedFuture(json.toByteArray(StandardCharsets.UTF_8))
        }
        .orElseGet {
            log.info("[DataResolver] DB MISS, Nexon API call required for userIgn={}", userIgn)
            fetchFromNexonApiAndSave(ocid)
        }

    private fun fetchFromNexonApiAndSave(ocid: String): CompletableFuture<ByteArray> = AsyncUtils.withTimeout(
        dataProvider.getRawEquipmentData(ocid),
        NEXON_API_TIMEOUT_SECONDS,
        TimeUnit.SECONDS,
        "NexonEquipmentAPI",
    )
        .thenApplyAsync({ compressedData ->
            executor.executeWithFallback(
                {
                    val json = GzipUtils.decompress(compressedData)
                    dbWorker
                        .persistRawJson(ocid, json)
                        .exceptionally { ex ->
                            log.warn("[DataResolver] DB save failed (non-blocking): {}", ex.message)
                            dbSaveFailCounter.increment()
                            null
                        }
                    compressedData
                },
                { ex ->
                    log.warn("[DataResolver] Decompress failed (non-blocking): {}", ex.message)
                    compressedData
                },
                TaskContext.of("DataResolver", "DecompressAndSave", ocid),
            )
        }, expectationExecutor)

    companion object {
        private val log = LoggerFactory.getLogger(EquipmentDataResolver::class.java)
        private const val NEXON_API_TIMEOUT_SECONDS = 25L
    }
}
