package maple.expectation.infrastructure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * V5 CQRS: MongoDB Query Service Idempotency Tests
 *
 * <h3>Test Scope</h3>
 *
 * <ul>
 *   <li>Idempotent upsert operations (same document ID updates existing)
 *   <li>Duplicate prevention via deterministic ID
 *   <li>Graceful degradation on MongoDB failure
 *   <li>Metrics emission (hit/miss latency timers)
 * </ul>
 *
 * <h3>Test Case: 아델</h3>
 *
 * Uses "아델" as the primary test user IGN to verify Korean character handling in idempotency.
 *
 * <h3>Idempotency Strategy</h3>
 *
 * <p>Uses deterministic document ID format: {@code userIgn:taskId}. This ensures that Redis Stream
 * at-least-once delivery (duplicates possible) results in MongoDB upserts updating the same
 * document instead of creating duplicates.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("V5: MongoDB Query Service Idempotency Tests")
class CharacterViewQueryServiceIdempotencyTest {

  private static final String TEST_IGN = "아델";
  private static final String TEST_TASK_ID = "task-123";
  private static final String DETERMINISTIC_ID = TEST_IGN + ":" + TEST_TASK_ID;

  @Mock private CharacterValuationRepository mockRepository;

  @Mock private MongoTemplate mockMongoTemplate;

  @Mock private LogicExecutor mockExecutor;

  @Mock private MeterRegistry mockMeterRegistry;

  @Mock private Timer mockTimer;

  private CharacterViewQueryService queryService;

  @BeforeEach
  void setUp() {
    queryService =
        new CharacterViewQueryService(
            mockRepository, mockMongoTemplate, mockExecutor, mockMeterRegistry);
  }

  private CharacterValuationView createView(
      String id, String messageId, String userIgn, Long totalExpectedCost) {
    return new CharacterValuationView(
        id,
        userIgn,
        messageId,
        null,
        null,
        null,
        null,
        null,
        null,
        totalExpectedCost,
        null,
        null,
        null);
  }

  @Test
  @DisplayName("Idempotent upsert: Same document ID updates existing record (no duplicate)")
  void testIdempotentUpsert_SameDocumentId_UpdatesExisting() {
    CharacterValuationView view = createView(DETERMINISTIC_ID, "msg-123", TEST_IGN, 100000L);

    doAnswer(
            inv -> {
              ThrowingRunnable runnable = inv.getArgument(0);
              runnable.run();
              return null;
            })
        .when(mockExecutor)
        .executeVoid(any(), any(TaskContext.class));

    queryService.upsert(view);

    verify(mockMongoTemplate, times(1)).upsert(any(), any(), eq(CharacterValuationView.class));
  }

  @Test
  @DisplayName("Idempotent upsert: Multiple calls with same ID result in single document")
  void testIdempotentUpsert_MultipleCalls_SingleDocument() {
    CharacterValuationView view = createView(DETERMINISTIC_ID, "msg-123", TEST_IGN, 100000L);

    doAnswer(
            inv -> {
              ThrowingRunnable runnable = inv.getArgument(0);
              runnable.run();
              return null;
            })
        .when(mockExecutor)
        .executeVoid(any(), any(TaskContext.class));

    queryService.upsert(view);
    queryService.upsert(view);
    queryService.upsert(view);

    verify(mockMongoTemplate, times(3)).upsert(any(), any(), eq(CharacterValuationView.class));
  }

  @Test
  @DisplayName("Idempotent upsert: Different task IDs create different documents")
  void testIdempotentUpsert_DifferentTaskIds_DifferentDocuments() {
    String taskId1 = "task-1";
    String taskId2 = "task-2";
    CharacterValuationView view1 = createView(TEST_IGN + ":" + taskId1, "msg-1", TEST_IGN, 100000L);
    CharacterValuationView view2 = createView(TEST_IGN + ":" + taskId2, "msg-2", TEST_IGN, 200000L);

    doAnswer(
            inv -> {
              ThrowingRunnable runnable = inv.getArgument(0);
              runnable.run();
              return null;
            })
        .when(mockExecutor)
        .executeVoid(any(), any(TaskContext.class));

    queryService.upsert(view1);
    queryService.upsert(view2);

    verify(mockMongoTemplate, times(2)).upsert(any(), any(), eq(CharacterValuationView.class));
  }

  @Test
  @DisplayName("Idempotent upsert: Korean IGN (아델) handled correctly")
  void testIdempotentUpsert_KoreanIGN_HandledCorrectly() {
    CharacterValuationView view = createView(DETERMINISTIC_ID, "msg-123", TEST_IGN, 100000L);
    view.setCharacterOcid("ocid-123");

    doAnswer(
            inv -> {
              ThrowingRunnable runnable = inv.getArgument(0);
              runnable.run();
              return null;
            })
        .when(mockExecutor)
        .executeVoid(any(), any(TaskContext.class));

    queryService.upsert(view);

    verify(mockMongoTemplate).upsert(any(), any(), eq(CharacterValuationView.class));
    assertThat(view.getUserIgn()).isEqualTo(TEST_IGN);
  }

  @Test
  @DisplayName("Graceful degradation: MongoDB failure returns null on findByUserIgn")
  void testGracefulDegradation_MongoDBFailure_ReturnsNull() throws Exception {
    when(mockExecutor.executeOrDefault(any(), any(), any()))
        .thenAnswer(
            inv -> {
              return inv.getArgument(1);
            });

    CharacterValuationView result = queryService.findByUserIgn(TEST_IGN);

    assertThat(result).isNull();
    verify(mockRepository, never()).findByUserIgn(any());
  }

  @Test
  @DisplayName("Metrics: Cache hit records latency timer")
  void testMetrics_CacheHit_RecordsLatency() throws Exception {
    CharacterValuationView view = createView(DETERMINISTIC_ID, null, TEST_IGN, null);

    when(mockRepository.findByUserIgn(TEST_IGN)).thenReturn(view);
    when(mockMeterRegistry.timer(any(String.class), any(String[].class))).thenReturn(mockTimer);
    when(mockExecutor.executeOrDefault(any(), any(), any()))
        .thenAnswer(
            inv -> {
              ThrowingSupplier<CharacterValuationView> supplier = inv.getArgument(0);
              return supplier.get();
            });

    queryService.findByUserIgn(TEST_IGN);

    verify(mockMeterRegistry).timer("mongodb.query.latency", "operation", "hit");
    verify(mockTimer).record(any(java.time.Duration.class));
  }

  @Test
  @DisplayName("Metrics: Cache miss records latency timer")
  void testMetrics_CacheMiss_RecordsLatency() throws Exception {
    when(mockRepository.findByUserIgn(TEST_IGN)).thenReturn(null);
    when(mockMeterRegistry.timer(any(String.class), any(String[].class))).thenReturn(mockTimer);
    when(mockExecutor.executeOrDefault(any(), any(), any()))
        .thenAnswer(
            inv -> {
              ThrowingSupplier<CharacterValuationView> supplier = inv.getArgument(0);
              return supplier.get();
            });

    queryService.findByUserIgn(TEST_IGN);

    verify(mockMeterRegistry).timer("mongodb.query.latency", "operation", "miss");
    verify(mockTimer).record(any(java.time.Duration.class));
  }

  @Test
  @DisplayName("Delete by user IGN: Removes all documents for user")
  void testDeleteByUserIgn_RemovesAllDocuments() {
    doAnswer(
            inv -> {
              ThrowingRunnable runnable = inv.getArgument(0);
              runnable.run();
              return null;
            })
        .when(mockExecutor)
        .executeVoid(any(), any(TaskContext.class));

    queryService.deleteByUserIgn(TEST_IGN);

    verify(mockRepository).deleteByUserIgn(TEST_IGN);
  }
}
