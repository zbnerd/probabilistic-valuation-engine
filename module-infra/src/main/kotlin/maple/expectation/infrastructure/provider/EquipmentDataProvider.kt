package maple.expectation.infrastructure.provider

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.util.GzipUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class EquipmentDataProvider(
    private val fetchProvider: EquipmentFetchProvider,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    @Value("\${app.optimization.use-compression:true}") private val useCompression: Boolean,
) {
    private val logger = LoggerFactory.getLogger(EquipmentDataProvider::class.java)

    /** ✅ [V3] 원본 데이터 획득 (비동기 및 실행기 통합) */
    fun getRawEquipmentData(ocid: String): CompletableFuture<ByteArray?> {
        val context = TaskContext.of("EquipmentProvider", "GetRawData", ocid)

        // supplyAsync 내부 로직을 executor로 보호하여 예외 및 지표 추적
        return CompletableFuture.supplyAsync {
            executor.execute({ fetchProvider.fetchWithCache(ocid) }, context)
        }
            .thenApply { response -> serializeResponse(response, context) }
    }

    /**
     * 🔥 [FAN-OUT] 원본 데이터 획득 - Nexon API 병렬 호출 (getCharacterBasic, getItemData)
     *
     * <p>Purpose: Latency 최적화를 위해 2개 Nexon API를 병렬로 호출
     * <p>호출되는 API: getCharacterBasic, getItemDataByOcid
     *
     * @param ocid 캐릭터 OCID
     * @return 장비 데이터 ByteArray (getItemData만 직렬화하여 반환)
     */
    fun getRawEquipmentDataWithFanout(ocid: String): CompletableFuture<ByteArray?> {
        val context = TaskContext.of("EquipmentProvider", "GetRawDataFanout", ocid)

        return fetchProvider.fetchAllWithCacheAsync(ocid)
            .thenApply { (basic, item) ->
                // Equipment만 직렬화하여 반환 (기존 호환성 유지)
                serializeResponse(item, context)
            }
    }

    /** ✅ [V2] Response DTO 획득 */
    fun getEquipmentResponse(ocid: String): CompletableFuture<EquipmentResponse?> = CompletableFuture.completedFuture(
        executor.execute(
            { fetchProvider.fetchWithCache(ocid) },
            TaskContext.of("EquipmentProvider", "GetResponse", ocid),
        ),
    )

    /**
     * Zero-Copy streaming (Issue #63) — non-blocking CF chain.
     *
     * GZIP-compressed data is streamed directly. Controller must set Content-Encoding: gzip header.
     *
     * @param ocid character OCID
     * @param os output stream (Content-Encoding: gzip required)
     * @return CF that completes when the write is done
     */
    fun streamRaw(ocid: String, os: OutputStream): CompletableFuture<Void> {
        val context = TaskContext.of("EquipmentProvider", "StreamRaw", ocid)

        return getRawEquipmentData(ocid).thenAccept { compressedData ->
            executor.executeWithTranslation(
                {
                    os.write(compressedData)
                    os.flush()
                    null
                },
                ExceptionTranslator.forFileIO(),
                context,
            )
        }
    }

    /** ✅ 직렬화 및 압축 로직 평탄화 JSON 처리 및 기술적 예외 레이어 분리 */
    private fun serializeResponse(response: EquipmentResponse, context: TaskContext): ByteArray = executor.executeWithTranslation(
        {
            // 1. JSON 직렬화
            val jsonString = objectMapper.writeValueAsString(response)

            // 2. 조건부 GZIP 압축
            if (useCompression) {
                GzipUtils.compress(jsonString)
            } else {
                jsonString.toByteArray(StandardCharsets.UTF_8)
            }
        },
        ExceptionTranslator.forJson(),
        context,
    )
}
