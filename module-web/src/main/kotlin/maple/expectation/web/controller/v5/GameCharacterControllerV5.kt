package maple.expectation.web.controller.v5

import jakarta.validation.constraints.NotBlank
import java.util.Optional
import java.util.concurrent.CompletableFuture
import maple.expectation.common.executor.TaskContext
import maple.expectation.core.domain.model.character.CharacterView
import maple.expectation.core.port.inbound.CalculationQueuePort
import maple.expectation.core.port.inbound.CharacterViewQueryPort
import maple.expectation.core.port.inbound.ExecutorPort
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.core.port.out.EquipmentFanOutPort
import maple.expectation.core.port.inbound.TaskReceipt
import maple.expectation.web.dto.v5.EquipmentExpectationResponseV5
import maple.expectation.web.mapper.CharacterViewMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * V5 CQRS 캐릭터 컨트롤러 (ADR-005 이관)
 *
 * **CQRS Pattern**
 * - Query Side: PostgreSQL CharacterValuationViewEntity (fast read 1-10ms)
 * - Command Side: Priority Queue + Calculation Worker
 * - Sync: PGMQ event queue → PostgreSQL upsert
 *
 * **ADR-005 Hexagonal Architecture**
 * - CharacterViewQueryPort: PostgreSQL 조회
 * - CalculationQueuePort: 큐 작업 추가
 * - EquipmentFanOutPort: FanOut Micro-Batch 프리페치 (fanout.enabled=true)
 *
 * **FanOut Integration (fanout.enabled=true)**
 * - PostgreSQL MISS 시 장비 데이터를 Micro-Batch Coalescing으로 프리페치
 * - 프리페치 후 Calculation Worker가 캐시된 데이터를 활용 (빠른 계산)
 * - Best-effort: 실패해도 큐잉은 정상 수행
 */
@RestController
@RequestMapping("/api/v5/characters")
@ConditionalOnProperty(name = ["v5.enabled"], havingValue = "true", matchIfMissing = false)
class GameCharacterControllerV5(
    private val queryPort: CharacterViewQueryPort,
    private val queuePort: CalculationQueuePort,
    private val executorPort: ExecutorPort,
    private val ocidPort: CharacterOcidPort,
    @Value("\${fanout.enabled:false}") private val fanOutEnabled: Boolean,
    private val fanOutPort: EquipmentFanOutPort,
) {

    /**
     * V5: 캐릭터 기대값 조회 (CQRS - PostgreSQL Read First)
     *
     * @param userIgn 캐릭터 IGN
     * @return V5 response DTO or 202 Accepted if calculation queued
     */
    @GetMapping("/{userIgn}/expectation")
    @PreAuthorize("permitAll()")
    fun getExpectationV5(
        @PathVariable @NotBlank userIgn: String,
    ): CompletableFuture<ResponseEntity<*>> {
        log.debug("[V5] Query expectation for: {}", maskIgn(userIgn))
        return CompletableFuture.supplyAsync { processPostgreSQLCacheFirstLookup(userIgn) }
    }

    private fun processPostgreSQLCacheFirstLookup(userIgn: String): ResponseEntity<*> {
        val context = TaskContext.of("V5Query", "CacheFirstLookup", userIgn)

        // 1. Query Side: Check PostgreSQL first via Port
        val cachedResult: Optional<EquipmentExpectationResponseV5> = executorPort.executeOrDefault(
            {
                queryPort.findByUserIgn(userIgn)
                    .map { CharacterViewMapper.toResponseDto(it) }
                    .orElse(Optional.empty())
            },
            Optional.empty(),
            context,
        )

        // 2. HIT: Return immediately (1-10ms)
        if (cachedResult.isPresent) {
            log.debug("[V5] PostgreSQL HIT: {}", maskIgn(userIgn))
            return ResponseEntity.ok(cachedResult.get())
        }

        // 3. MISS: Pre-warm equipment cache via FanOut (best-effort)
        if (fanOutEnabled) {
            preWarmEquipmentCache(userIgn, context)
        }

        // 4. Queue to Command Side via Port
        return queueCalculationTask(userIgn, false, context)
    }

    /**
     * FanOut Micro-Batch로 장비 데이터 프리페치 (Best-Effort)
     *
     * <p>OCID 해석 → EquipmentFanOutPort.preFetchByOcid():
     * <ul>
     *   <li>L1 Cache HIT → 즉시 반환 (0ms)</li>
     *   <li>In-Flight Coalescing → 기존 요청 대기</li>
     *   <li>Fast Lane → EquipmentFetchProvider.fetchWithCache()</li>
     *   <li>Batch Lane → NexonFanOutBatchLoader.load()</li>
     * </ul>
     *
     * <p>실패해도 큐잉은 정상 수행 (Best-Effort)
     */
    private fun preWarmEquipmentCache(userIgn: String, context: TaskContext) {
        executorPort.executeVoidJava({
            val ocid = ocidPort.resolveOcid(userIgn)
            if (ocid != null) {
                fanOutPort.preFetchByOcid(ocid)
                log.debug("[V5] FanOut pre-warm: ign={}, ocid={}", maskIgn(userIgn), ocid.take(8))
            }
        }, context)
    }

    /**
     * V5: 기대값 강제 재계산 (Cache Invalidation)
     *
     * @param userIgn 캐릭터 IGN
     * @return 202 Accepted if calculation queued
     */
    @PostMapping("/{userIgn}/expectation/recalculate")
    @PreAuthorize("permitAll()")
    fun recalculateExpectationV5(
        @PathVariable userIgn: String,
    ): CompletableFuture<ResponseEntity<*>> {
        log.info("[V5] Force recalculation requested: {}", maskIgn(userIgn))
        return CompletableFuture.supplyAsync { processCacheInvalidation(userIgn) }
    }

    private fun processCacheInvalidation(userIgn: String): ResponseEntity<*> {
        val context = TaskContext.of("V5Query", "InvalidateAndRecalculate", userIgn)

        // 1. Invalidate PostgreSQL cache via Port
        executorPort.executeVoidJava({ queryPort.deleteByUserIgn(userIgn) }, context)

        // 2. Queue with force=true via Port
        return queueCalculationTask(userIgn, true, context)
    }

    // ==================== Private Helper Methods ====================

    private fun queueCalculationTask(
        userIgn: String,
        forceRecalculation: Boolean,
        context: TaskContext,
    ): ResponseEntity<*> {
        val receipt = executorPort.executeOrDefault(
            { queuePort.offerHighPriorityWithReceipt(userIgn, forceRecalculation) },
            TaskReceipt.rejected(userIgn),
            context,
        )

        return if (receipt.queued) {
            log.info("[V5] PostgreSQL MISS, queued calculation: {} (taskId={})", maskIgn(userIgn), receipt.taskId)
            ResponseEntity.accepted()
                .header("X-Task-Id", receipt.taskId)
                .build<Unit>()
        } else {
            log.warn("[V5] Queue full, rejecting: {}", maskIgn(userIgn))
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Queue full, try again later")
        }
    }

    private fun maskIgn(ign: String?): String {
        if (ign == null || ign.length < 2) return "***"
        return ign[0] + "***" + ign.substring(ign.length - 1)
    }

    companion object {
        private val log = LoggerFactory.getLogger(GameCharacterControllerV5::class.java)
    }
}
