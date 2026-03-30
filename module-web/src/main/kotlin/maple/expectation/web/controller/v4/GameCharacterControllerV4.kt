package maple.expectation.web.controller.v4

import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import maple.expectation.core.port.inbound.AdmissionPort
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.out.PopularCharacterTrackerPort
import maple.expectation.core.domain.model.like.LikeToggleResult
import maple.expectation.core.domain.model.like.LikeToggleWithCount
import maple.expectation.core.port.inbound.LikeTogglePort
import maple.expectation.core.domain.model.security.AuthenticatedUser
import maple.expectation.response.ApiResponse
import maple.expectation.web.dto.v4.EquipmentExpectationResponseV4
import maple.expectation.web.dto.v4.LikeStatusResponse
import maple.expectation.web.dto.v4.LikeToggleResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * V4 캐릭터 컨트롤러 (ADR-005 이관)
 *
 * **ADR-005 Hexagonal Architecture**
 * - ExpectationV4Port: 기대값 계산
 * - PopularCharacterTrackerPort: 인기 캐릭터 추적
 *
 * **Issue #151: Bean Validation 적용**
 * - @Validated: 클래스 레벨 검증 활성화
 * - @NotBlank: @PathVariable IGN 검증
 */
@Validated
@RestController
@RequestMapping("/api/v4/characters")
class GameCharacterControllerV4(
    private val expectationPort: ExpectationV4Port,
    private val trackerPort: PopularCharacterTrackerPort,
    private val admissionPort: AdmissionPort,
    private val likeTogglePort: LikeTogglePort,
    @Qualifier("taskExecutor") private val taskExecutor: Executor,
) {

    @GetMapping("/{userIgn}/expectation")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    fun getExpectation(
        @PathVariable @NotBlank userIgn: String,
        @RequestParam(defaultValue = "false") force: Boolean,
        @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) acceptEncoding: String?,
    ): CompletableFuture<ResponseEntity<*>> {
        log.debug(
            "[V4] Expectation for: {} (force={}, gzip={})",
            maskIgn(userIgn),
            force,
            acceptsGzip(acceptEncoding),
        )

        // Auto Warmup
        trackerPort.recordAccess(userIgn)

        // 🔥 Fast Path: GZIP + force=false + L1 캐시 히트
        // NOTE: Fast path bypasses admission (already cached, no CPU work needed)
        if (acceptsGzip(acceptEncoding) && !force) {
            val fastPathResult = expectationPort.getGzipFromL1CacheDirect(userIgn)
            if (fastPathResult != null) {
                log.debug("[V4] L1 Fast Path HIT: {}", maskIgn(userIgn))
                return CompletableFuture.completedFuture(buildGzipResponse(fastPathResult))
            }
        }

        // 🔥 Cold Path: Cache miss → Admission Control → Port (sync)
        // All cold misses go through admission control for backpressure
        val key = "expectation:$userIgn" // For metrics/tracing

        return if (acceptsGzip(acceptEncoding)) {
            // GZIP 요청: admission → sync port → async response
            admissionPort.submitOrWait(key) {
                expectationPort.getGzipExpectation(userIgn, force)
            }.thenApplyAsync({ gzipBytes -> buildGzipResponse(gzipBytes ?: ByteArray(0)) }, taskExecutor)
        } else {
            // JSON 요청: admission → sync port → async response
            admissionPort.submitOrWait(key) {
                expectationPort.calculateExpectation(userIgn, force)
            }.thenApplyAsync({ this.buildJsonResponse(it) }, taskExecutor)
        }
    }

    private fun buildGzipResponse(gzipBytes: ByteArray): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_ENCODING, "gzip")
        .contentType(MediaType.APPLICATION_JSON)
        .contentLength(gzipBytes.size.toLong())
        .body(gzipBytes)

    @Suppress("UNCHECKED_CAST")
    private fun buildJsonResponse(response: Any): ResponseEntity<EquipmentExpectationResponseV4> = ResponseEntity.ok(response as EquipmentExpectationResponseV4)

    private fun acceptsGzip(acceptEncoding: String?): Boolean = acceptEncoding != null && acceptEncoding.lowercase().contains("gzip")

    @GetMapping("/{userIgn}/expectation/preset/{presetNo}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    fun getExpectationByPreset(
        @PathVariable userIgn: String,
        @PathVariable presetNo: Int,
    ): CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> {
        log.info("[V4] Expectation for {} preset {}", maskIgn(userIgn), presetNo)

        return expectationPort
            .calculateExpectationAsync(userIgn, false)
            .thenApplyAsync({ r -> r as EquipmentExpectationResponseV4 }, taskExecutor)
            .thenApplyAsync({ response -> ResponseEntity.ok(filterByPreset(response, presetNo)) }, taskExecutor)
    }

    @PostMapping("/{userIgn}/expectation/recalculate")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    fun recalculateExpectation(
        @PathVariable userIgn: String,
    ): CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> {
        log.info("[V4] Force recalculating expectation for: {}", maskIgn(userIgn))

        return expectationPort
            .calculateExpectationAsync(userIgn, true)
            .thenApplyAsync({ r -> r as EquipmentExpectationResponseV4 }, taskExecutor)
            .thenApplyAsync({ ResponseEntity.ok(it) }, taskExecutor)
    }

    private fun filterByPreset(
        response: EquipmentExpectationResponseV4,
        presetNo: Int,
    ): EquipmentExpectationResponseV4 {
        val filteredPresets = response.presets.filter { it.presetNo == presetNo }

        return EquipmentExpectationResponseV4(
            response.userIgn,
            response.calculatedAt,
            response.fromCache,
            if (filteredPresets.isEmpty()) 0.0 else filteredPresets[0].totalExpectedCost,
            if (filteredPresets.isEmpty()) "0" else filteredPresets[0].totalCostText,
            if (filteredPresets.isEmpty()) EquipmentExpectationResponseV4.CostBreakdownDto.empty() else filteredPresets[0].costBreakdown,
            response.maxPresetNo,
            filteredPresets,
        )
    }

    // === Like Endpoints (ADR-029) ===

    @PostMapping("/{userIgn}/like")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    fun toggleLike(
        @PathVariable @NotBlank userIgn: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ResponseEntity<ApiResponse<LikeToggleResponse>> {
        log.debug("[V4] Like toggle: target={} by={}", maskIgn(userIgn), maskIgn(user.userIgn))
        val toggleWithCount = likeTogglePort.toggleLikeWithCount(userIgn, user.accountId, user.myOcids)
        val response = LikeToggleResponse(
            targetUserIgn = userIgn,
            liked = toggleWithCount.result == LikeToggleResult.LIKED,
            likeCount = toggleWithCount.likeCount,
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/{userIgn}/like/status")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    fun getLikeStatus(
        @PathVariable @NotBlank userIgn: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ResponseEntity<ApiResponse<LikeStatusResponse>> {
        log.debug("[V4] Like status: target={} by={}", maskIgn(userIgn), maskIgn(user.userIgn))
        val liked = likeTogglePort.isLiked(userIgn, user.accountId)
        val likeCount = likeTogglePort.getLikeCount(userIgn)
        val response = LikeStatusResponse(
            targetUserIgn = userIgn,
            liked = liked,
            likeCount = likeCount,
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    private fun maskIgn(ign: String?): String {
        if (ign == null || ign.length < 2) return "***"
        return ign[0] + "***" + ign.substring(ign.length - 1)
    }

    companion object {
        private val log = LoggerFactory.getLogger(GameCharacterControllerV4::class.java)
        private const val FIRST_PRESET_INDEX = 0
    }
}
