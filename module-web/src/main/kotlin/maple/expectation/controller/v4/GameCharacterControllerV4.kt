package maple.expectation.controller.v4

import jakarta.validation.constraints.NotBlank
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.out.PopularCharacterTrackerPort
import maple.expectation.web.dto.v4.EquipmentExpectationResponseV4
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture

/**
 * V4 캐릭터 컨트롤러 (ADR-005 이관)
 *
 * **ADR-005 Hexagonal Architecture**
 * - ExpectationV4Port: 기대값 계산
 * - PopularCharacterTrackerPort: 인기 캐릭터 추적
 */
@RestController
@RequestMapping("/api/v4/characters")
class GameCharacterControllerV4(
    private val expectationPort: ExpectationV4Port,
    private val trackerPort: PopularCharacterTrackerPort
) {

    @GetMapping("/{userIgn}/expectation")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    fun getExpectation(
        @PathVariable @NotBlank userIgn: String,
        @RequestParam(defaultValue = "false") force: Boolean,
        @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) acceptEncoding: String?
    ): CompletableFuture<ResponseEntity<*>> {
        log.debug(
            "[V4] Expectation for: {} (force={}, gzip={})",
            maskIgn(userIgn),
            force,
            acceptsGzip(acceptEncoding)
        )

        // Auto Warmup
        trackerPort.recordAccess(userIgn)

        // Fast Path: GZIP + force=false + L1 캐시 히트
        if (acceptsGzip(acceptEncoding) && !force) {
            val fastPathResult = expectationPort.getGzipFromL1CacheDirect(userIgn)
            if (fastPathResult != null) {
                log.debug("[V4] L1 Fast Path HIT: {}", maskIgn(userIgn))
                return CompletableFuture.completedFuture(buildGzipResponse(fastPathResult))
            }
        }

        // GZIP 응답
        return if (acceptsGzip(acceptEncoding)) {
            expectationPort
                .getGzipExpectationAsync(userIgn, force)
                .thenApply { gzipBytes -> buildGzipResponse(gzipBytes ?: ByteArray(0)) }
        } else {
            // JSON 응답
            expectationPort
                .calculateExpectationAsync(userIgn, force)
                .thenApply { this.buildJsonResponse(it) }
        }
    }

    private fun buildGzipResponse(gzipBytes: ByteArray): ResponseEntity<ByteArray> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_ENCODING, "gzip")
            .contentType(MediaType.APPLICATION_JSON)
            .contentLength(gzipBytes.size.toLong())
            .body(gzipBytes)
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildJsonResponse(response: Any): ResponseEntity<EquipmentExpectationResponseV4> {
        return ResponseEntity.ok(response as EquipmentExpectationResponseV4)
    }

    private fun acceptsGzip(acceptEncoding: String?): Boolean {
        return acceptEncoding != null && acceptEncoding.lowercase().contains("gzip")
    }

    @GetMapping("/{userIgn}/expectation/preset/{presetNo}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    fun getExpectationByPreset(
        @PathVariable userIgn: String,
        @PathVariable presetNo: Int
    ): CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> {
        log.info("[V4] Expectation for {} preset {}", maskIgn(userIgn), presetNo)

        return expectationPort
            .calculateExpectationAsync(userIgn, false)
            .thenApply { r -> r as EquipmentExpectationResponseV4 }
            .thenApply { response -> ResponseEntity.ok(filterByPreset(response, presetNo)) }
    }

    @PostMapping("/{userIgn}/expectation/recalculate")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    fun recalculateExpectation(
        @PathVariable userIgn: String
    ): CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> {
        log.info("[V4] Force recalculating expectation for: {}", maskIgn(userIgn))

        return expectationPort
            .calculateExpectationAsync(userIgn, true)
            .thenApply { r -> r as EquipmentExpectationResponseV4 }
            .thenApply { ResponseEntity.ok(it) }
    }

    private fun filterByPreset(
        response: EquipmentExpectationResponseV4,
        presetNo: Int
    ): EquipmentExpectationResponseV4 {
        val filteredPresets = response.presets.filter { it.presetNo == presetNo }

        return EquipmentExpectationResponseV4(
            response.userIgn,
            response.calculatedAt,
            response.fromCache,
            if (filteredPresets.isEmpty()) BigDecimal.ZERO else filteredPresets[0].totalExpectedCost,
            if (filteredPresets.isEmpty()) "0" else filteredPresets[0].totalCostText,
            if (filteredPresets.isEmpty()) EquipmentExpectationResponseV4.CostBreakdownDto.empty() else filteredPresets[0].costBreakdown,
            response.maxPresetNo,
            filteredPresets
        )
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
