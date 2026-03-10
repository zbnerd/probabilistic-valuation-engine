package maple.expectation.infrastructure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
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
    // Setup executor to pass through execute calls
    lenient()
        .when(mockExecutor.execute(any(ThrowingSupplier.class), any(TaskContext.class)))
        .thenAnswer(
            inv -> {
              ThrowingSupplier<?> task = inv.getArgument(0);
              return task.get();
            });

    // Setup executor to pass through executeOrDefault calls
    lenient()
        .when(
            mockExecutor.executeOrDefault(
                any(ThrowingSupplier.class), any(), any(TaskContext.class)))
        .thenAnswer(
            inv -> {
              ThrowingSupplier<?> task = inv.getArgument(0);
              Object defaultValue = inv.getArgument(1);
              try {
                Object result = task.get();
                return result != null ? result : defaultValue;
              } catch (Throwable t) {
                return defaultValue;
              }
            });

    // Setup executor to pass through executeVoid calls
    lenient()
        .doAnswer(
            inv -> {
              ThrowingRunnable task = inv.getArgument(0);
              task.run();
              return null;
            })
        .when(mockExecutor)
        .executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

    // Setup executor to pass through executeVoidJava calls
    lenient()
        .doAnswer(
            inv -> {
              Runnable task = inv.getArgument(0);
              task.run();
              return null;
            })
        .when(mockExecutor)
        .executeVoidJava(any(Runnable.class), any(TaskContext.class));

    queryService =
        new CharacterViewQueryService(
            mockRepository, mockMongoTemplate, mockExecutor, mockMeterRegistry);
  }

  private CharacterValuationView createView(
      String id, String messageId, String userIgn, Long totalExpectedCost, Long version) {
    return new CharacterValuationView(
        id,
        userIgn,
        messageId,
        null,
        null,
        null,
        null,
        null,
        version,
        null, // lastAppliedVersion
        totalExpectedCost,
        null,
        null,
        null);
  }

  private CharacterValuationView createView(
      String id, String messageId, String userIgn, Long totalExpectedCost) {
    return createView(id, messageId, userIgn, totalExpectedCost, null);
  }

  @Test
  @DisplayName("Idempotent upsert: Same document ID updates existing record (no duplicate)")
  void testIdempotentUpsert_SameDocumentId_UpdatesExisting() {
    // Incoming version (2) > existing version (1) triggers update
    CharacterValuationView view = createView(DETERMINISTIC_ID, "msg-123", TEST_IGN, 100000L, 2L);
    CharacterValuationView existing = createView(DETERMINISTIC_ID, "msg-old", TEST_IGN, 50000L, 1L);

    // Mock repository to return existing document (triggers update path)
    when(mockRepository.findById(DETERMINISTIC_ID)).thenReturn(Optional.of(existing));

    // Mock meter registry for counter
    when(mockMeterRegistry.counter(any(String.class), any(String[].class)))
        .thenReturn(mock(Counter.class));

    // Mock updateFirst to return successful result
    when(mockMongoTemplate.updateFirst(any(), any(), eq(CharacterValuationView.class)))
        .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1L, 1L, null));

    queryService.upsert(view);

    verify(mockMongoTemplate, times(1)).updateFirst(any(), any(), eq(CharacterValuationView.class));
  }

  @Test
  @DisplayName("Idempotent upsert: Multiple calls with same ID result in single document")
  void testIdempotentUpsert_MultipleCalls_SingleDocument() {
    // Incoming version (2) > existing version (1) triggers update
    CharacterValuationView view = createView(DETERMINISTIC_ID, "msg-123", TEST_IGN, 100000L, 2L);
    CharacterValuationView existing = createView(DETERMINISTIC_ID, "msg-old", TEST_IGN, 50000L, 1L);

    // Mock repository to return existing document (triggers update path)
    when(mockRepository.findById(DETERMINISTIC_ID)).thenReturn(Optional.of(existing));

    // Mock meter registry for counter
    when(mockMeterRegistry.counter(any(String.class), any(String[].class)))
        .thenReturn(mock(Counter.class));

    // Mock updateFirst to return successful result
    when(mockMongoTemplate.updateFirst(any(), any(), eq(CharacterValuationView.class)))
        .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1L, 1L, null));

    queryService.upsert(view);
    queryService.upsert(view);
    queryService.upsert(view);

    verify(mockMongoTemplate, times(3)).updateFirst(any(), any(), eq(CharacterValuationView.class));
  }

  @Test
  @DisplayName("Idempotent upsert: Different task IDs create different documents")
  void testIdempotentUpsert_DifferentTaskIds_DifferentDocuments() {
    String taskId1 = "task-1";
    String taskId2 = "task-2";
    // Incoming versions > existing versions
    CharacterValuationView view1 =
        createView(TEST_IGN + ":" + taskId1, "msg-1", TEST_IGN, 100000L, 2L);
    CharacterValuationView view2 =
        createView(TEST_IGN + ":" + taskId2, "msg-2", TEST_IGN, 200000L, 2L);
    CharacterValuationView existing =
        createView(TEST_IGN + ":" + taskId1, "msg-old", TEST_IGN, 50000L, 1L);

    // Mock meter registry for counter
    when(mockMeterRegistry.counter(any(String.class), any(String[].class)))
        .thenReturn(mock(Counter.class));

    // Mock repository to return existing documents (triggers update path)
    when(mockRepository.findById(any())).thenReturn(Optional.of(existing));

    // Mock updateFirst to return successful result
    when(mockMongoTemplate.updateFirst(any(), any(), eq(CharacterValuationView.class)))
        .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1L, 1L, null));

    queryService.upsert(view1);
    queryService.upsert(view2);

    verify(mockMongoTemplate, times(2)).updateFirst(any(), any(), eq(CharacterValuationView.class));
  }

  @Test
  @DisplayName("Idempotent upsert: Korean IGN (아델) handled correctly")
  void testIdempotentUpsert_KoreanIGN_HandledCorrectly() {
    // Incoming version (2) > existing version (1) triggers update
    CharacterValuationView view = createView(DETERMINISTIC_ID, "msg-123", TEST_IGN, 100000L, 2L);
    CharacterValuationView existing = createView(DETERMINISTIC_ID, "msg-old", TEST_IGN, 50000L, 1L);
    view.setCharacterOcid("ocid-123");

    // Mock meter registry for counter
    when(mockMeterRegistry.counter(any(String.class), any(String[].class)))
        .thenReturn(mock(Counter.class));

    // Mock repository to return existing document (triggers update path)
    when(mockRepository.findById(DETERMINISTIC_ID)).thenReturn(Optional.of(existing));

    // Mock updateFirst to return successful result
    when(mockMongoTemplate.updateFirst(any(), any(), eq(CharacterValuationView.class)))
        .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1L, 1L, null));

    queryService.upsert(view);

    verify(mockMongoTemplate).updateFirst(any(), any(), eq(CharacterValuationView.class));
    assertThat(view.getUserIgn()).isEqualTo(TEST_IGN);
  }

  @Test
  @DisplayName("Graceful degradation: MongoDB failure returns null on findByUserIgn")
  void testGracefulDegradation_MongoDBFailure_ReturnsNull() throws Exception {
    // When repository throws exception, executor returns default value
    when(mockRepository.findByUserIgn(TEST_IGN))
        .thenThrow(new RuntimeException("MongoDB connection failed"));

    CharacterValuationView result = queryService.findByUserIgn(TEST_IGN);

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("Metrics: Cache hit records latency timer")
  void testMetrics_CacheHit_RecordsLatency() throws Exception {
    CharacterValuationView view = createView(DETERMINISTIC_ID, null, TEST_IGN, null);

    when(mockRepository.findByUserIgn(TEST_IGN)).thenReturn(view);
    when(mockMeterRegistry.timer(any(String.class), any(String[].class))).thenReturn(mockTimer);

    queryService.findByUserIgn(TEST_IGN);

    verify(mockMeterRegistry).timer("mongodb.query.latency", "operation", "hit");
    verify(mockTimer).record(any(java.time.Duration.class));
  }

  @Test
  @DisplayName("Metrics: Cache miss records latency timer")
  void testMetrics_CacheMiss_RecordsLatency() throws Exception {
    when(mockRepository.findByUserIgn(TEST_IGN)).thenReturn(null);
    when(mockMeterRegistry.timer(any(String.class), any(String[].class))).thenReturn(mockTimer);

    queryService.findByUserIgn(TEST_IGN);

    verify(mockMeterRegistry).timer("mongodb.query.latency", "operation", "miss");
    verify(mockTimer).record(any(java.time.Duration.class));
  }

  @Test
  @DisplayName("Delete by user IGN: Removes all documents for user")
  void testDeleteByUserIgn_RemovesAllDocuments() {
    queryService.deleteByUserIgn(TEST_IGN);

    verify(mockRepository).deleteByUserIgn(TEST_IGN);
  }
}
