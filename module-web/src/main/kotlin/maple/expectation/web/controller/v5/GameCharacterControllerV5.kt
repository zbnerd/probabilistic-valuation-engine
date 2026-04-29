package maple.expectation.web.controller.v5

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import maple.expectation.common.executor.TaskContext
import maple.expectation.core.port.inbound.CalculationQueuePort
import maple.expectation.core.port.inbound.CharacterViewQueryPort
import maple.expectation.core.port.inbound.ExecutorPort
import maple.expectation.core.port.inbound.TaskReceipt
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.dto.ErrorResponse
import maple.expectation.web.dto.v5.EquipmentExpectationResponseV5
import maple.expectation.web.mapper.CharacterViewMapper
import maple.expectation.web.validation.ValidIgn
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * V5 CQRS 캐릭터 컨트롤러 — Read Path (ADR Three-Path Independence)
 *
 * **Read Path Boundary**: HTTP + View 조회 + enqueue only.
 * - NexonApiClient 직접/간접 호출 없음
 * - 계산 로직 없음
 * - business data write 없음 (calculation_jobs INSERT + calculation_queue enqueue만 허용)
 */
@Validated
@RestController
@RequestMapping("/api/v5/characters")
@ConditionalOnProperty(name = ["v5.enabled"], havingValue = "true", matchIfMissing = false)
class GameCharacterControllerV5(
    private val queryPort: CharacterViewQueryPort,
    private val queuePort: CalculationQueuePort,
    private val executorPort: ExecutorPort,
    @Qualifier("expectationComputeExecutor") private val computeExecutor: Executor,
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
        @PathVariable @NotBlank @ValidIgn userIgn: String,
        @RequestParam(defaultValue = "1") @Min(1) @Max(3) presetNo: Int = 1,
    ): CompletableFuture<ResponseEntity<*>> {
        val normalizedIgn = userIgn.trim()
        log.debug("[V5] Query expectation for: {}", maskIgn(normalizedIgn))
        return CompletableFuture.supplyAsync({ processPostgreSQLCacheFirstLookup(normalizedIgn, presetNo) }, computeExecutor)
    }

    private fun processPostgreSQLCacheFirstLookup(userIgn: String, presetNo: Int): ResponseEntity<*> {
        val context = TaskContext.of("V5Query", "CacheFirstLookup", userIgn)

        // 1. Query Side: Check PostgreSQL first via Port
        val cachedResult: Optional<EquipmentExpectationResponseV5> = executorPort.executeOrDefault(
            {
                queryPort.findByUserIgn(userIgn)
                    .filter { view -> view.presets?.any { it.presetNo == presetNo } ?: false }
                    .map { CharacterViewMapper.toResponseDto(it) }
                    .orElse(Optional.empty())
            },
            Optional.empty(),
            context,
        )

        // 2. HIT: Return immediately (1-10ms)
        if (cachedResult.isPresent) {
            log.debug("[V5] PostgreSQL HIT: {} (presetNo={})", maskIgn(userIgn), presetNo)
            return ResponseEntity.ok(cachedResult.get())
        }

        // 3. MISS: Queue to Command Side via Port
        return queueCalculationTask(userIgn, false, presetNo, context)
    }

    /**
     * V5: 기대값 강제 재계산 (force recalculation via queue)
     *
     * @param userIgn 캐릭터 IGN
     * @return 202 Accepted if calculation queued
     */
    @PostMapping("/{userIgn}/expectation/recalculate")
    @PreAuthorize("permitAll()")
    fun recalculateExpectationV5(
        @PathVariable @NotBlank @ValidIgn userIgn: String,
        @RequestParam(defaultValue = "1") @Min(1) @Max(3) presetNo: Int = 1,
    ): CompletableFuture<ResponseEntity<*>> {
        val normalizedIgn = userIgn.trim()
        log.info("[V5] Force recalculation requested: {} (presetNo={})", maskIgn(normalizedIgn), presetNo)
        return CompletableFuture.supplyAsync({ processCacheInvalidation(normalizedIgn, presetNo) }, computeExecutor)
    }

    private fun processCacheInvalidation(userIgn: String, presetNo: Int): ResponseEntity<*> {
        val context = TaskContext.of("V5Query", "ForceRecalculate", userIgn)
        return queueCalculationTask(userIgn, true, presetNo, context)
    }

    // ==================== Private Helper Methods ====================

    private fun queueCalculationTask(
        userIgn: String,
        forceRecalculation: Boolean,
        presetNo: Int,
        context: TaskContext,
    ): ResponseEntity<*> {
        val receipt = executorPort.executeOrDefault(
            { queuePort.offerHighPriorityWithReceipt(userIgn, forceRecalculation, presetNo) },
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
                .body(ErrorResponse.from(CommonErrorCode.SERVICE_UNAVAILABLE))
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
