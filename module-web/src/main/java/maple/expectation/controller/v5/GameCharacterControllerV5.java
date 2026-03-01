package maple.expectation.controller.v5;

import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.inbound.CalculationQueuePort;
import maple.expectation.core.port.inbound.CharacterViewQueryPort;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.mongodb.CharacterValuationView;
import maple.expectation.web.dto.v5.EquipmentExpectationResponseV5;
import maple.expectation.web.mapper.CharacterViewMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V5 CQRS 캐릭터 컨트롤러 (ADR-005 이관)
 *
 * <h3>CQRS Pattern</h3>
 *
 * <ul>
 *   <li><b>Query Side:</b> MongoDB CharacterValuationView (fast read 1-10ms)
 *   <li><b>Command Side:</b> Priority Queue + Calculation Worker
 *   <li><b>Sync:</b> Redis Stream character-sync → MongoDB upsert
 * </ul>
 *
 * <h3>ADR-005 Hexagonal Architecture</h3>
 *
 * <ul>
 *   <li>CharacterViewQueryPort: MongoDB 조회
 *   <li>CalculationQueuePort: 큐 작업 추가
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v5/characters")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class GameCharacterControllerV5 {

  private final CharacterViewQueryPort queryPort;
  private final CalculationQueuePort queuePort;
  private final LogicExecutor executor;

  /**
   * V5: 캐릭터 기대값 조회 (CQRS - MongoDB Read First)
   *
   * @param userIgn 캐릭터 IGN
   * @return V5 response DTO or 202 Accepted if calculation queued
   */
  @GetMapping("/{userIgn}/expectation")
  @PreAuthorize("permitAll()")
  public CompletableFuture<ResponseEntity<?>> getExpectationV5(
      @PathVariable @NotBlank String userIgn) {

    log.debug("[V5] Query expectation for: {}", maskIgn(userIgn));

    return CompletableFuture.supplyAsync(() -> processMongoDBCacheFirstLookup(userIgn));
  }

  private ResponseEntity<?> processMongoDBCacheFirstLookup(String userIgn) {
    TaskContext context = TaskContext.of("V5Query", "CacheFirstLookup", userIgn);

    // 1. Query Side: Check MongoDB first via Port
    Optional<EquipmentExpectationResponseV5> cachedResult =
        executor.executeOrDefault(
            () -> {
              Object view = queryPort.findByUserIgn(userIgn);
              if (view instanceof CharacterValuationView valuationView) {
                return CharacterViewMapper.toResponseDto(valuationView);
              }
              return Optional.<EquipmentExpectationResponseV5>empty();
            },
            Optional.empty(),
            context);

    // 2. HIT: Return immediately (1-10ms)
    if (cachedResult.isPresent()) {
      log.debug("[V5] MongoDB HIT: {}", maskIgn(userIgn));
      return ResponseEntity.ok(cachedResult.get());
    }

    // 3. MISS: Queue to Command Side via Port
    return queueCalculationTask(userIgn, false, context);
  }

  /**
   * V5: 기대값 강제 재계산 (Cache Invalidation)
   *
   * @param userIgn 캐릭터 IGN
   * @return 202 Accepted if calculation queued
   */
  @PostMapping("/{userIgn}/expectation/recalculate")
  @PreAuthorize("permitAll()")
  public CompletableFuture<ResponseEntity<?>> recalculateExpectationV5(
      @PathVariable String userIgn) {

    log.info("[V5] Force recalculation requested: {}", maskIgn(userIgn));

    return CompletableFuture.supplyAsync(() -> processCacheInvalidation(userIgn));
  }

  private ResponseEntity<?> processCacheInvalidation(String userIgn) {
    TaskContext context = TaskContext.of("V5Query", "InvalidateAndRecalculate", userIgn);

    // 1. Invalidate MongoDB cache via Port
    executor.executeVoidJava(() -> queryPort.deleteByUserIgn(userIgn), context);

    // 2. Queue with force=true via Port
    return queueCalculationTask(userIgn, true, context);
  }

  // ==================== Private Helper Methods ====================

  private ResponseEntity<?> queueCalculationTask(
      String userIgn, boolean forceRecalculation, TaskContext context) {

    boolean queued =
        executor.executeOrDefault(
            () -> queuePort.offerHighPriority(userIgn, forceRecalculation), false, context);

    if (queued) {
      log.info("[V5] MongoDB MISS, queued calculation: {}", maskIgn(userIgn));
      return ResponseEntity.accepted().build();
    } else {
      log.warn("[V5] Queue full, rejecting: {}", maskIgn(userIgn));
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body("Queue full, try again later");
    }
  }

  private String maskIgn(String ign) {
    if (ign == null || ign.length() < 2) return "***";
    return ign.charAt(0) + "***" + ign.substring(ign.length() - 1);
  }
}
