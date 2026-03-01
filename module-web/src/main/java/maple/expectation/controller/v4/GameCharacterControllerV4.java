package maple.expectation.controller.v4;

import jakarta.validation.constraints.NotBlank;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.inbound.ExpectationV4Port;
import maple.expectation.core.port.out.PopularCharacterTrackerPort;
import maple.expectation.web.dto.v4.EquipmentExpectationResponseV4;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * V4 캐릭터 컨트롤러 (ADR-005 이관)
 *
 * <h3>ADR-005 Hexagonal Architecture</h3>
 *
 * <ul>
 *   <li>ExpectationV4Port: 기대값 계산
 *   <li>PopularCharacterTrackerPort: 인기 캐릭터 추적
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v4/characters")
public class GameCharacterControllerV4 {

  private static final int FIRST_PRESET_INDEX = 0;

  private final ExpectationV4Port expectationPort;
  private final PopularCharacterTrackerPort trackerPort;

  @GetMapping("/{userIgn}/expectation")
  @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
  public CompletableFuture<ResponseEntity<?>> getExpectation(
      @PathVariable @NotBlank String userIgn,
      @RequestParam(defaultValue = "false") boolean force,
      @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding) {

    log.debug(
        "[V4] Expectation for: {} (force={}, gzip={})",
        maskIgn(userIgn),
        force,
        acceptsGzip(acceptEncoding));

    // Auto Warmup
    trackerPort.recordAccess(userIgn);

    // Fast Path: GZIP + force=false + L1 캐시 히트
    if (acceptsGzip(acceptEncoding) && !force) {
      byte[] fastPathResult = expectationPort.getGzipFromL1CacheDirect(userIgn);
      if (fastPathResult != null) {
        log.debug("[V4] L1 Fast Path HIT: {}", maskIgn(userIgn));
        return CompletableFuture.completedFuture(buildGzipResponse(fastPathResult));
      }
    }

    // GZIP 응답
    if (acceptsGzip(acceptEncoding)) {
      return expectationPort
          .getGzipExpectationAsync(userIgn, force)
          .thenApply(this::buildGzipResponse);
    }

    // JSON 응답
    return expectationPort
        .calculateExpectationAsync(userIgn, force)
        .thenApply(this::buildJsonResponse);
  }

  private ResponseEntity<byte[]> buildGzipResponse(byte[] gzipBytes) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_ENCODING, "gzip")
        .contentType(MediaType.APPLICATION_JSON)
        .contentLength(gzipBytes.length)
        .body(gzipBytes);
  }

  @SuppressWarnings("unchecked")
  private ResponseEntity<EquipmentExpectationResponseV4> buildJsonResponse(Object response) {
    return ResponseEntity.ok((EquipmentExpectationResponseV4) response);
  }

  private boolean acceptsGzip(String acceptEncoding) {
    return acceptEncoding != null && acceptEncoding.toLowerCase().contains("gzip");
  }

  @GetMapping("/{userIgn}/expectation/preset/{presetNo}")
  @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
  public CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> getExpectationByPreset(
      @PathVariable String userIgn, @PathVariable Integer presetNo) {

    log.info("[V4] Expectation for {} preset {}", maskIgn(userIgn), presetNo);

    return expectationPort
        .calculateExpectationAsync(userIgn, false)
        .thenApply(r -> (EquipmentExpectationResponseV4) r)
        .thenApply(response -> ResponseEntity.ok(filterByPreset(response, presetNo)));
  }

  @PostMapping("/{userIgn}/expectation/recalculate")
  @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
  public CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> recalculateExpectation(
      @PathVariable String userIgn) {

    log.info("[V4] Force recalculating expectation for: {}", maskIgn(userIgn));

    return expectationPort
        .calculateExpectationAsync(userIgn, true)
        .thenApply(r -> (EquipmentExpectationResponseV4) r)
        .thenApply(ResponseEntity::ok);
  }

  private EquipmentExpectationResponseV4 filterByPreset(
      EquipmentExpectationResponseV4 response, Integer presetNo) {
    var filteredPresets =
        response.getPresets().stream().filter(p -> p.getPresetNo() == presetNo).toList();

    return new EquipmentExpectationResponseV4(
        response.getUserIgn(),
        response.getCalculatedAt(),
        response.isFromCache(),
        filteredPresets.isEmpty()
            ? java.math.BigDecimal.ZERO
            : filteredPresets.get(FIRST_PRESET_INDEX).getTotalExpectedCost(),
        filteredPresets.isEmpty()
            ? "0"
            : filteredPresets.get(FIRST_PRESET_INDEX).getTotalCostText(),
        filteredPresets.isEmpty()
            ? EquipmentExpectationResponseV4.CostBreakdownDto.empty()
            : filteredPresets.get(FIRST_PRESET_INDEX).getCostBreakdown(),
        response.getMaxPresetNo(),
        filteredPresets);
  }

  private String maskIgn(String ign) {
    if (ign == null || ign.length() < 2) return "***";
    return ign.charAt(0) + "***" + ign.substring(ign.length() - 1);
  }
}
