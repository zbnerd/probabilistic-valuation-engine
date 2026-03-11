package maple.expectation.infrastructure.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import java.time.Instant
import java.util.concurrent.CompletableFuture
import maple.expectation.core.port.out.NexonDataCollectorPort
import maple.expectation.domain.repository.GameCharacterRepository
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.CharacterBasicResponse
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.infrastructure.persistence.repository.NexonRawDataStore
import maple.expectation.infrastructure.queue.pgmq.NexonDataQueueProducer
import maple.expectation.infrastructure.ratelimit.PostgresRateLimiter
import maple.expectation.infrastructure.ratelimit.RateLimiter
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Nexon API 데이터 수집 스케줄러 (ADR-006)
 *
 * <h3>역할</h3>
 * <p>NexonApiOutboxScheduler를 대체하여 PGMQ 기반 데이터 수집 파이프라인 구현
 *
 * <h3>처리 흐름</h3>
 * <ol>
 *   <li>5분마다 활성 캐릭터 목록 조회</li>
 *   <li>Rate Limit 준수하며 Nexon API 호출</li>
 *   <li>원본 JSON 데이터를 nexon_raw_data 테이블에 저장 (JSONB)</li>
 *   <li>calculation_queue에 메시지 발행하여 CalculationWorker에게 처리 위임</li>
 * </ol>
 *
 * <h3>CLAUDE.md 준수</h3>
 * <ul>
 *   <li>섹션 12: LogicExecutor 패턴으로 try-catch 제거</li>
 *   <li>섹션 11: ServerBaseException으로 예외 변환</li>
 *   <li>섹션 15: 람다 3줄 초과 시 메서드 추출</li>
 * </ul>
 *
 * @see NexonDataCollectorPort
 * @see PostgresRateLimiter
 * @see NexonRawDataStore
 * @see NexonDataQueueProducer
 */
@Component
@ConditionalOnBean(RateLimiter::class)
@ConditionalOnProperty(
    name = ["scheduler.nexon-api-collector.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class NexonApiCollectorScheduler(
    private val gameCharacterRepository: GameCharacterRepository,
    private val nexonApiClient: NexonApiClient,
    private val rawDataReader: NexonRawDataStore,
    private val queueProducer: NexonDataQueueProducer,
    private val rateLimiter: RateLimiter,
    private val executor: LogicExecutor,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(NexonApiCollectorScheduler::class.java)

    /**
     * Nexon API 데이터 수집 (5분 간격)
     *
     * <p>Circuit Breaker로 Nexon API 장애 시 빠른 실패
     */
    @Scheduled(fixedRate = 300000)
    @CircuitBreaker(name = "nexon-api", fallbackMethod = "collectFallback")
    fun collectNexonData() {
        val context = TaskContext.of("Scheduler", "NexonApiCollector")

        executor.executeVoid(
            { processBatch() },
            context,
        )
    }

    /**
     * 배치 처리 메인 로직
     */
    private fun processBatch() {
        log.info("[NexonApiCollector] Starting scheduled collection")

        // 활성 캐릭터 목록 조회 (최근 30일 내 업데이트)
        val characters = gameCharacterRepository.findActiveCharacters()

        if (characters.isEmpty()) {
            log.info("[NexonApiCollector] No active characters found")
            return
        }

        // 배치 사이즈 제한 (한 번에 최대 100개)
        val batch = characters.take(100)

        log.info("[NexonApiCollector] Processing {} characters", batch.size)

        val results = processCharacters(batch)

        log.info(
            "[NexonApiCollector] Collection completed: success={}, failed={}",
            results.successCount,
            results.failureCount,
        )
    }

    /**
     * 캐릭터별 데이터 수집 처리
     *
     * @param characters 처리할 캐릭터 목록
     * @return 처리 결과 집계
     */
    private fun processCharacters(characters: List<maple.expectation.core.domain.model.character.GameCharacter>): CollectionResult {
        var successCount = 0
        var failureCount = 0
        var rateLimitedCount = 0

        characters.forEach { character ->
            val ocid = character.characterId.value
            val userIgn = character.userIgn.value

            val result = processSingleCharacter(ocid, userIgn)

            when (result) {
                ProcessResult.SUCCESS -> successCount++
                ProcessResult.FAILURE -> failureCount++
                ProcessResult.RATE_LIMITED -> rateLimitedCount++
            }
        }

        return CollectionResult(successCount, failureCount, rateLimitedCount)
    }

    /**
     * 단일 캐릭터 처리
     *
     * @param ocid 캐릭터 OCID
     * @param userIgn 사용자 IGN
     * @return 처리 결과
     */
    private fun processSingleCharacter(ocid: String, userIgn: String): ProcessResult {
        val context = TaskContext.of("NexonApiCollector", "ProcessCharacter", userIgn)

        return executor.executeOrDefault(
            { doProcessSingleCharacter(ocid, userIgn) },
            ProcessResult.FAILURE,
            context,
        )
    }

    /**
     * 단일 캐릭터 처리 내부 로직
     *
     * <h3>처리 단계</h3>
     * <ol>
     *   <li>Rate Limit 확인</li>
     *   <li>Nexon API 호출 (비동기)</li>
     *   <li>원본 JSON 데이터 저장</li>
     *   <li>Calculation Queue에 메시지 발행</li>
     * </ol>
     */
    private fun doProcessSingleCharacter(ocid: String, userIgn: String): ProcessResult {
        // 1. Rate Limit 확인
        val consumeResult = rateLimiter.tryConsume(ocid)
        if (!consumeResult.allowed) {
            log.debug("[NexonApiCollector] Rate limited: ign={}, retryAfter={}s", userIgn, consumeResult.retryAfterSeconds)
            return ProcessResult.RATE_LIMITED
        }

        // 2. Nexon API 호출 (비동기 병렬)
        val basicFuture = nexonApiClient.getCharacterBasic(ocid)
        val itemFuture = nexonApiClient.getItemDataByOcid(ocid)

        // 3. 비동기 결과 조합
        val combinedData = combineApiResponses(ocid, basicFuture, itemFuture)

        // 4. 원본 JSON 데이터 저장
        saveRawData(ocid, combinedData)

        // 5. Calculation Queue에 메시지 발행
        queueProducer.publish(ocid, userIgn)

        log.debug("[NexonApiCollector] Successfully collected and queued: ign={}", userIgn)
        return ProcessResult.SUCCESS
    }

    /**
     * Nexon API 응답 조합
     *
     * @param ocid 캐릭터 OCID
     * @param basicFuture 기본 정보 Future
     * @param itemFuture 장비 정보 Future
     * @return 조합된 JSON 데이터
     */
    private fun combineApiResponses(
        ocid: String,
        basicFuture: CompletableFuture<CharacterBasicResponse>,
        itemFuture: CompletableFuture<EquipmentResponse>,
    ): String {
        val basic = basicFuture.get()
        val item = itemFuture.get()

        val combined = mapOf(
            "ocid" to ocid,
            "basic" to basic,
            "item_data" to item,
            "collected_at" to Instant.now().toString(),
        )

        return objectMapper.writeValueAsString(combined)
    }

    /**
     * 원본 데이터 저장
     *
     * @param ocid 캐릭터 OCID
     * @param jsonData JSON 데이터
     */
    private fun saveRawData(ocid: String, jsonData: String) {
        val context = TaskContext.of("NexonApiCollector", "SaveRaw", ocid)

        executor.executeVoid(
            { rawDataReader.save(ocid, jsonData) },
            context,
        )
    }

    /**
     * Circuit Breaker OPEN 시 Fallback
     *
     * <p>Nexon API 장애 시 로그만 기록하고 빠르게 복귀
     *
     * @param e 예외
     */
    private fun collectFallback(e: Throwable) {
        log.warn("[NexonApiCollector] Circuit Breaker OPEN - skipping collection", e)
    }

    // ==================== Inner Classes ====================

    /**
     * 처리 결과
     */
    private enum class ProcessResult {
        /** 처리 성공 */
        SUCCESS,

        /** 처리 실패 */
        FAILURE,

        /** Rate Limit 초과 */
        RATE_LIMITED,
    }

    /**
     * 배치 처리 결과 집계
     */
    private data class CollectionResult(
        val successCount: Int,
        val failureCount: Int,
        val rateLimitedCount: Int,
    )
}
