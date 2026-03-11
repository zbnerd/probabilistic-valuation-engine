package maple.expectation.web.controller.v5

import jakarta.validation.constraints.NotBlank
import java.util.Optional
import java.util.concurrent.CompletableFuture
import maple.expectation.core.port.inbound.CalculationQueuePort
import maple.expectation.core.port.inbound.CharacterViewQueryPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity
import maple.expectation.web.dto.v5.EquipmentExpectationResponseV5
import maple.expectation.web.mapper.CharacterViewMapper
import org.slf4j.LoggerFactory
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
 * - Sync: PGMQ v5_event_queue → PostgreSQL upsert
 *
 * **ADR-005 Hexagonal Architecture**
 * - CharacterViewQueryPort: PostgreSQL 조회
 * - CalculationQueuePort: 큐 작업 추가
 */
@RestController
@RequestMapping("/api/v5/characters")
@ConditionalOnProperty(name = ["v5.enabled"], havingValue = "true", matchIfMissing = false)
class GameCharacterControllerV5(
    private val queryPort: CharacterViewQueryPort,
    private val queuePort: CalculationQueuePort,
    private val executor: LogicExecutor,
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
        val cachedResult: Optional<EquipmentExpectationResponseV5> = executor.executeOrDefault(
            {
                val view = queryPort.findByUserIgn(userIgn)
                if (view is CharacterValuationViewEntity) {
                    CharacterViewMapper.toResponseDto(view)
                } else {
                    Optional.empty<EquipmentExpectationResponseV5>()
                }
            },
            Optional.empty<EquipmentExpectationResponseV5>(),
            context,
        )

        // 2. HIT: Return immediately (1-10ms)
        if (cachedResult.isPresent) {
            log.debug("[V5] PostgreSQL HIT: {}", maskIgn(userIgn))
            return ResponseEntity.ok(cachedResult.get())
        }

        // 3. MISS: Queue to Command Side via Port
        return queueCalculationTask(userIgn, false, context)
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
        executor.executeVoidJava({ queryPort.deleteByUserIgn(userIgn) }, context)

        // 2. Queue with force=true via Port
        return queueCalculationTask(userIgn, true, context)
    }

    // ==================== Private Helper Methods ====================

    private fun queueCalculationTask(
        userIgn: String,
        forceRecalculation: Boolean,
        context: TaskContext,
    ): ResponseEntity<*> {
        val queued = executor.executeOrDefault(
            { queuePort.offerHighPriority(userIgn, forceRecalculation) },
            false,
            context,
        )

        return if (queued) {
            log.info("[V5] PostgreSQL MISS, queued calculation: {}", maskIgn(userIgn))
            ResponseEntity.accepted().build<Unit>()
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
