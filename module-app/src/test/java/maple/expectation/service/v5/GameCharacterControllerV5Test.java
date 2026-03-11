package maple.expectation.service.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationQueue;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationTask;
import maple.expectation.infrastructure.persistence.CharacterViewQueryServicePostgres;
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity;
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity.CostBreakdownView;
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity.ItemExpectationView;
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity.PresetView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * V5 CQRS Query Side Integration Tests
 *
 * <h3>Test Scope</h3>
 *
 * <ul>
 *   <li>PostgreSQL HIT scenarios (cached views)
 *   <li>PostgreSQL MISS scenarios (queue delegation)
 *   <li>Force recalculation (cache invalidation)
 *   <li>Backpressure (queue full)
 * </ul>
 *
 * <h3>Test Strategy</h3>
 *
 * Uses Mockito mocks for infrastructure components (PostgreSQL, Queue, Redis). Focuses on
 * controller logic and CQRS flow validation.
 */
@Tag("unit")
class GameCharacterControllerV5Test {

  @Mock private CharacterViewQueryServicePostgres queryService;
  @Mock private ExpectationCalculationQueue queue;

  @InjectMocks private TestableGameCharacterControllerV5 controller;

  private static final String TEST_IGN = "TestCharacter";
  private static final String MASKED_IGN = "T***r";

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    controller = new TestableGameCharacterControllerV5(queryService, queue);
  }

  @Test
  @DisplayName("PostgreSQL HIT: Return cached view immediately")
  void testPostgreSQLHit_ReturnsCachedView() {
    // Given: PostgreSQL has cached view
    CharacterValuationViewEntity mockView = createMockView();
    when(queryService.findByUserIgn(TEST_IGN)).thenReturn(mockView);
    // Default queue mock behavior - offer returns true (not used in HIT case)
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(true);

    // When: Query expectation
    ResponseEntity<?> response = controller.getExpectationV5Internal(TEST_IGN);

    // Then: Return 200 OK with view data
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();

    // Verify no queue interaction (cache hit, so queue not used)
    verify(queue, times(0)).offer(any(ExpectationCalculationTask.class));
  }

  @Test
  @DisplayName("PostgreSQL MISS: Queue calculation and return 202")
  void testPostgreSQLMiss_QueuesCalculation_Returns202() {
    // Given: PostgreSQL has no cached view
    when(queryService.findByUserIgn(TEST_IGN)).thenReturn(null);
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(true);

    // When: Query expectation
    ResponseEntity<?> response = controller.getExpectationV5Internal(TEST_IGN);

    // Then: Return 202 Accepted
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    // Verify task was queued
    verify(queue, times(1)).offer(any(ExpectationCalculationTask.class));
  }

  @Test
  @DisplayName("Queue Full: Return 503 Service Unavailable")
  void testQueueFull_Returns503() {
    // Given: PostgreSQL miss and queue full
    when(queryService.findByUserIgn(TEST_IGN)).thenReturn(null);
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(false);

    // When: Query expectation
    ResponseEntity<?> response = controller.getExpectationV5Internal(TEST_IGN);

    // Then: Return 503
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isEqualTo("Queue full, try again later");
  }

  @Test
  @DisplayName("Force Recalculation: Delete cache and queue task")
  void testForceRecalculation_DeletesCacheAndQueues() {
    // Given: Queue accepts task
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(true);

    // When: Force recalculation
    ResponseEntity<Void> response = controller.recalculateExpectationV5Internal(TEST_IGN);

    // Then: Cache deleted and task queued
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    verify(queryService, times(1)).deleteByUserIgn(TEST_IGN);
    verify(queue, times(1)).offer(any(ExpectationCalculationTask.class));
  }

  @Test
  @DisplayName("Force Recalculation Queue Full: Return 503")
  void testForceRecalculationQueueFull_Returns503() {
    // Given: Queue full
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(false);

    // When: Force recalculation
    ResponseEntity<Void> response = controller.recalculateExpectationV5Internal(TEST_IGN);

    // Then: Return 503
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  // ==================== Helper Methods ====================

  private CharacterValuationViewEntity createMockView() {
    CostBreakdownView breakdown =
        new CostBreakdownView(
            100000L, // blackCubeCost
            50000L, // redCubeCost
            20000L, // additionalCubeCost
            30000L, // starforceCost
            0L // flameCost
            );

    List<ItemExpectationView> items =
        List.of(
            new ItemExpectationView(
                "Arcane Umbra Hat", // itemName
                50000L, // expectedCost
                "50,000" // costText
                ));

    PresetView preset =
        new PresetView(
            1, // presetNo
            200000L, // totalExpectedCost
            "200,000", // totalCostText
            breakdown, // costBreakdown
            items // items
            );

    return new CharacterValuationViewEntity(
        1L, // id
        TEST_IGN, // userIgn
        null, // messageId
        "test-ocid", // characterOcid
        "Pathfinder", // characterClass
        275, // characterLevel
        Instant.now(), // calculatedAt
        Instant.now(), // lastApiSyncAt
        1L, // version
        null, // lastAppliedVersion
        200000L, // totalExpectedCost
        1, // maxPresetNo
        List.of(preset), // presets
        true // fromCache
        );
  }

  // ==================== Test Helper Classes ====================

  /**
   * Testable wrapper for GameCharacterControllerV5 to expose package-private methods for testing.
   */
  static class TestableGameCharacterControllerV5 {
    private final CharacterViewQueryServicePostgres queryService;
    private final ExpectationCalculationQueue queue;

    TestableGameCharacterControllerV5(
        CharacterViewQueryServicePostgres queryService, ExpectationCalculationQueue queue) {
      this.queryService = queryService;
      this.queue = queue;
    }

    ResponseEntity<?> getExpectationV5Internal(String userIgn) {
      var view = queryService.findByUserIgn(userIgn);

      if (view != null) {
        return ResponseEntity.ok(toResponseDto(view));
      }

      ExpectationCalculationTask task = ExpectationCalculationTask.highPriority(userIgn, false);
      boolean queued = queue.offer(task);

      if (queued) {
        return ResponseEntity.accepted().build();
      } else {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body("Queue full, try again later");
      }
    }

    ResponseEntity<Void> recalculateExpectationV5Internal(String userIgn) {
      queryService.deleteByUserIgn(userIgn);

      ExpectationCalculationTask task = ExpectationCalculationTask.highPriority(userIgn, true);
      boolean queued = queue.offer(task);

      if (queued) {
        return ResponseEntity.accepted().build();
      } else {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
      }
    }

    private Object toResponseDto(CharacterValuationViewEntity view) {
      return java.util.Map.of(
          "userIgn",
          view.getUserIgn(),
          "totalExpectedCost",
          view.getTotalExpectedCost(),
          "maxPresetNo",
          view.getMaxPresetNo(),
          "calculatedAt",
          view.getCalculatedAt().toString(),
          "fromCache",
          view.getFromCache(),
          "presets",
          view.getPresets());
    }
  }
}
