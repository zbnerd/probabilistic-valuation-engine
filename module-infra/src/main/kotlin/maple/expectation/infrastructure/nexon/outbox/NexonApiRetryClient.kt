package maple.expectation.infrastructure.nexon.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.domain.v2.NexonApiOutbox
import maple.expectation.error.exception.ExternalServiceException
import maple.expectation.infrastructure.executor.CheckedLogicExecutor
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.CharacterBasicResponse
import maple.expectation.infrastructure.external.dto.v2.CharacterOcidResponse
import maple.expectation.infrastructure.external.dto.v2.CubeHistoryResponse
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.util.ExceptionUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.util.concurrent.TimeUnit

/**
 * Nexon API 재시도 클라이언트 구현 (N19)
 *
 * <h3>책임</h3>
 *
 * <ul>
 *   <li>Outbox에 적재된 실패한 API 호출을 재시도
 *   <li>Event Type에 따라 적절한 NexonApiClient 메서드 호출
 *   <li>성공/실패 결과를 반환하여 Processor가 상태 업데이트
 * </ul>
 *
 * <h3>예외 처리 정책</h3>
 *
 * <ul>
 *   <li>4xx 오류: 비즈니스 예외로 간주, 재시도 무의미 → 실패 반환
 *   <li>5xx/네트워크 오류: 일시적 장애로 간주, 재시도 의미 있음 → 실패 반환 (Processor가 재시도)
 *   <li>타임아웃: 10초 타임아웃 적용
 * </ul>
 *
 * @see maple.expectation.service.v2.outbox.NexonApiOutboxProcessor
 */
@Component
class NexonApiRetryClient(
    private val nexonApiClient: NexonApiClient,
    private val checkedExecutor: CheckedLogicExecutor,
    private val executor: LogicExecutor,
    private val metrics: NexonApiOutboxMetrics,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(NexonApiRetryClient::class.java)

    @Value("\${app.nexon.api.timeout:10}")
    private var apiTimeoutSeconds: Long = 10

    /**
     * Outbox 항목 처리
     *
     * @param outbox 처리할 Outbox 항목
     * @return 처리 성공 여부
     */
    fun processOutboxEntry(outbox: NexonApiOutbox): Boolean {
        val context = TaskContext.of("NexonApiRetry", "ProcessEntry", outbox.requestId)

        return checkedExecutor.executeUnchecked(
            { doRetry(outbox) },
            context
        ) { e ->
            ExternalServiceException(
                "Nexon API Outbox retry failed: ${outbox.requestId}",
                e
            )
        }
    }

    /**
     * Outbox 항목 재시도 로직
     *
     * <p>Event Type에 따라 적절한 API 메서드 호출
     */
    private fun doRetry(outbox: NexonApiOutbox): Boolean {
        val eventType = outbox.eventType
        val payload = outbox.payload

        log.info("[Retry] Outbox 항목 재시도: requestId={}, eventType={}", outbox.requestId, eventType)

        val context = TaskContext.of("NexonApiRetry", "DoRetry", outbox.requestId)

        return executor.executeOrCatch(
            {
                when (eventType) {
                    NexonApiOutbox.NexonApiEventType.GET_OCID -> retryGetOcid(payload ?: "")
                    NexonApiOutbox.NexonApiEventType.GET_CHARACTER_BASIC -> retryGetCharacterBasic(payload ?: "")
                    NexonApiOutbox.NexonApiEventType.GET_ITEM_DATA -> retryGetItemData(payload ?: "")
                    NexonApiOutbox.NexonApiEventType.GET_CUBES -> retryGetCubes(payload ?: "")
                    null -> {
                        log.error("[Retry] Event type is null: requestId={}", outbox.requestId)
                        false
                    }
                }
            },
            { e ->
                log.error(
                    "[Retry] 재시도 실패: requestId={}, eventType={}",
                    outbox.requestId,
                    eventType,
                    e
                )
                metrics.incrementApiCallRetry()
                false
            },
            context
        )
    }

    /** OCID 조회 재시도 */
    private fun retryGetOcid(characterName: String): Boolean {
        val context = TaskContext.of("NexonApiRetry", "RetryGetOcid", characterName)

        return executor.executeOrCatch(
            {
                val response = nexonApiClient
                    .getOcidByCharacterName(characterName)
                    .orTimeout(apiTimeoutSeconds, TimeUnit.SECONDS)
                    .join()

                log.info("[Retry] OCID 조회 성공: name={}, ocid={}", characterName, response.ocid)
                metrics.incrementApiCallSuccess()
                true
            },
            { e -> handleRetryFailure("GET_OCID", characterName, e) },
            context
        )
    }

    /** 캐릭터 기본 정보 조회 재시도 */
    private fun retryGetCharacterBasic(ocid: String): Boolean {
        val context = TaskContext.of("NexonApiRetry", "RetryGetCharacterBasic", ocid)

        return executor.executeOrCatch(
            {
                val response = nexonApiClient
                    .getCharacterBasic(ocid)
                    .orTimeout(apiTimeoutSeconds, TimeUnit.SECONDS)
                    .join()

                log.info(
                    "[Retry] Character Basic 조회 성공: ocid={}, world={}",
                    ocid,
                    response.worldName
                )
                metrics.incrementApiCallSuccess()
                true
            },
            { e -> handleRetryFailure("GET_CHARACTER_BASIC", ocid, e) },
            context
        )
    }

    /** 장비 데이터 조회 재시도 */
    private fun retryGetItemData(ocid: String): Boolean {
        val context = TaskContext.of("NexonApiRetry", "RetryGetItemData", ocid)

        return executor.executeOrCatch(
            {
                val response = nexonApiClient
                    .getItemDataByOcid(ocid)
                    .orTimeout(apiTimeoutSeconds, TimeUnit.SECONDS)
                    .join()

                log.info("[Retry] Item Data 조회 성공: ocid={}", ocid)
                metrics.incrementApiCallSuccess()
                true
            },
            { e -> handleRetryFailure("GET_ITEM_DATA", ocid, e) },
            context
        )
    }

    /** 큐브 데이터 조회 재시도 */
    private fun retryGetCubes(ocid: String): Boolean {
        val context = TaskContext.of("NexonApiRetry", "RetryGetCubes", ocid)

        return executor.executeOrCatch(
            {
                val response = nexonApiClient
                    .getCubeHistory(ocid)
                    .orTimeout(apiTimeoutSeconds, TimeUnit.SECONDS)
                    .join()

                log.info("[Retry] Cube History 조회 성공: ocid={}", ocid)
                metrics.incrementApiCallSuccess()
                true
            },
            { e -> handleRetryFailure("GET_CUBES", ocid, e) },
            context
        )
    }

    /**
     * 재시도 실패 처리
     *
     * <p>4xx 오류: 재시도 무의미 → 실패 반환
     *
     * <p>5xx/네트워크 오류: 일시적 장애 → 실패 반환 (Processor가 재시도)
     */
    private fun handleRetryFailure(eventType: String, payload: String, e: Throwable): Boolean {
        val root = ExceptionUtils.unwrapAsyncException(e)

        // 4xx 클라이언트 오류: 재시도 무의미
        if (root is WebClientResponseException && root.statusCode.is4xxClientError) {
            log.warn(
                "[Retry] 4xx 오류로 재시도 중단: eventType={}, status={}, payload={}",
                eventType,
                root.statusCode,
                maskPayload(payload)
            )
            return false
        }

        // 5xx/네트워크/타임아웃: 일시적 장애로 간주, 실패 반환 (Processor가 계속 재시도)
        log.warn(
            "[Retry] 일시적 장애 발생: eventType={}, payload={}, error={}",
            eventType,
            maskPayload(payload),
            root?.message
        )
        metrics.incrementApiCallRetry()
        return false
    }

    /** PII 마스킹 (로그 안전성 확보) */
    private fun maskPayload(payload: String?): String {
        if (payload == null || payload.length <= 4) {
            return "***"
        }
        return payload.substring(0, 4) + "***"
    }
}
