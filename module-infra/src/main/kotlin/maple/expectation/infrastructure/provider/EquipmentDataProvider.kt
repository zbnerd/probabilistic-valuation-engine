package maple.expectation.infrastructure.provider

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.util.GzipUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

@Component
class EquipmentDataProvider(
    private val fetchProvider: EquipmentFetchProvider,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    @Value("\${app.optimization.use-compression:true}") private val useCompression: Boolean
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

    /** ✅ [V2] Response DTO 획득 */
    fun getEquipmentResponse(ocid: String): CompletableFuture<EquipmentResponse?> {
        return CompletableFuture.completedFuture(
            executor.execute(
                { fetchProvider.fetchWithCache(ocid) },
                TaskContext.of("EquipmentProvider", "GetResponse", ocid)
            )
        )
    }

    /**
     * Zero-Copy 스트리밍 (Issue #63)
     *
     * GZIP 압축된 데이터를 그대로 전송합니다. Controller에서 Content-Encoding: gzip 헤더를 설정해야 합니다.
     *
     * 최적화 효과
     * - GZIP 압축 해제 → String → getBytes 변환 제거
     * - CPU 사용량 감소
     * - 메모리 할당 최소화
     *
     * @param ocid 캐릭터 OCID
     * @param os 출력 스트림 (Content-Encoding: gzip 필요)
     */
    fun streamRaw(ocid: String, os: OutputStream) {
        val context = TaskContext.of("EquipmentProvider", "StreamRaw", ocid)

        executor.executeVoid(
            {
                val compressedData = getRawEquipmentData(ocid).join()

                executor.executeWithTranslation(
                    {
                        os.write(compressedData)
                        os.flush()
                        null
                    },
                    ExceptionTranslator.forFileIO(),
                    context
                )
            },
            context
        )
    }

    /** ✅ 직렬화 및 압축 로직 평탄화 JSON 처리 및 기술적 예외 레이어 분리 */
    private fun serializeResponse(response: EquipmentResponse, context: TaskContext): ByteArray {
        return executor.executeWithTranslation(
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
            context
        )
    }
}
