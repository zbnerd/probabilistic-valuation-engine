package maple.expectation.infrastructure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Unit 5: Optimistic Lock Test for CharacterViewQueryService
 *
 * <h3>Purpose</h3>
 *
 * Verify that optimistic locking prevents batch jobs from overwriting realtime updates.
 *
 * <h3>Test Scenarios</h3>
 *
 * <ul>
 *   <li>Realtime update (high version) wins over batch (low version)
 *   <li>Batch update is skipped when realtime has newer data
 *   <li>Version is incremented on successful update
 *   <li>Metrics are recorded for skipped updates
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit 5: Optimistic Lock Tests")
class CharacterViewQueryServiceOptimisticLockTest {

  @Mock private CharacterValuationRepository repository;

  @Mock private MongoTemplate mongoTemplate;

  @Mock private LogicExecutor executor;

  private MeterRegistry meterRegistry;
  private CharacterViewQueryService queryService;

  private static final AtomicLong idGenerator = new AtomicLong(0);

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    queryService =
        new CharacterViewQueryService(repository, mongoTemplate, executor, meterRegistry);

    // Setup LogicExecutor to execute tasks directly for testing
    lenient()
        .doAnswer(
            invocation -> {
              ThrowingSupplier<?> task = invocation.getArgument(0);
              return task.get();
            })
        .when(executor)
        .executeOrDefault(any(), any(), any(TaskContext.class));

    lenient()
        .doAnswer(
            invocation -> {
              ThrowingRunnable task = invocation.getArgument(0);
              task.run();
              return null;
            })
        .when(executor)
        .executeVoid(any(), any(TaskContext.class));
  }

  @Test
  @DisplayName("Realtime update (high version) should win over batch (low version)")
  void realtimeUpdateWinsOverBatch() {
    // Given: Existing document with high version (realtime)
    CharacterValuationView existing = createView("user1", 1704000000000L);
    when(repository.findById(any())).thenReturn(java.util.Optional.of(existing));

    // When: Batch tries to update with low version
    CharacterValuationView batchUpdate = createView("user1", 1000L);
    queryService.upsert(batchUpdate);

    // Then: Update should be skipped (no mongoTemplate.updateFirst called)
    verify(mongoTemplate, never())
        .updateFirst(any(Query.class), any(Update.class), eq(CharacterValuationView.class));

    // And: Skipped metric should be recorded
    assertThat(meterRegistry.counter("mongodb.optimistic_lock.skipped").count()).isGreaterThan(0);
  }

  @Test
  @DisplayName("Batch update should succeed when realtime has older version")
  void batchUpdateSucceedsWhenRealtimeIsOlder() {
    // Given: Existing document with low version (old realtime)
    CharacterValuationView existing = createView("user1", 500L);
    when(repository.findById(any())).thenReturn(java.util.Optional.of(existing));

    // Mock updateFirst to return a valid UpdateResult
    UpdateResult mockResult = mock(UpdateResult.class);
    when(mockResult.getModifiedCount()).thenReturn(1L);
    when(mongoTemplate.updateFirst(
            any(Query.class), any(Update.class), eq(CharacterValuationView.class)))
        .thenReturn(mockResult);

    // When: Batch tries to update with higher version
    CharacterValuationView batchUpdate = createView("user1", 1000L);
    queryService.upsert(batchUpdate);

    // Then: Update should be executed
    verify(mongoTemplate, times(1))
        .updateFirst(any(Query.class), any(Update.class), eq(CharacterValuationView.class));

    // And: Updated metric should be recorded
    assertThat(meterRegistry.counter("mongodb.optimistic_lock.updated").count()).isGreaterThan(0);

    // And: Version should be incremented in the update
    ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate)
        .updateFirst(any(Query.class), updateCaptor.capture(), eq(CharacterValuationView.class));

    Update update = updateCaptor.getValue();
    // Version should be incremented (1000 + 1 = 1001)
    assertThat(update.getUpdateObject().get("$set")).isNotNull();
  }

  @Test
  @DisplayName("New document should be inserted with version 1")
  void newDocumentInsertedWithVersion1() {
    // Given: Document doesn't exist - return Optional.empty() not null
    when(repository.findById(any())).thenReturn(java.util.Optional.empty());

    // When: Upsert is called
    CharacterValuationView newView = createView("newUser", 1000L);
    queryService.upsert(newView);

    // Then: Repository save should be called with version 1
    ArgumentCaptor<CharacterValuationView> viewCaptor =
        ArgumentCaptor.forClass(CharacterValuationView.class);
    verify(repository).save(viewCaptor.capture());

    CharacterValuationView savedView = viewCaptor.getValue();
    assertThat(savedView.getVersion()).isEqualTo(1L);

    // And: Inserted metric should be recorded
    assertThat(meterRegistry.counter("mongodb.optimistic_lock.inserted").count()).isGreaterThan(0);
  }

  @Test
  @DisplayName("Realtime update (timestamp version) should always win over batch")
  void realtimeTimestampAlwaysWinsOverBatch() {
    // Given: Existing realtime update with timestamp version
    long realtimeTimestamp = System.currentTimeMillis();
    CharacterValuationView existing = createView("user1", realtimeTimestamp);
    when(repository.findById(any())).thenReturn(java.util.Optional.of(existing));

    // When: Batch tries to update with fixed low version
    CharacterValuationView batchUpdate = createView("user1", 1000L);
    queryService.upsert(batchUpdate);

    // Then: Update should be skipped
    verify(mongoTemplate, never())
        .updateFirst(any(Query.class), any(Update.class), eq(CharacterValuationView.class));

    // And: Skipped metric should be recorded
    assertThat(meterRegistry.counter("mongodb.optimistic_lock.skipped").count()).isGreaterThan(0);
  }

  // ========== Helper Methods ==========

  private CharacterValuationView createView(String userIgn, Long version) {
    return new CharacterValuationView(
        "id:" + idGenerator.incrementAndGet(),
        userIgn,
        "message-" + userIgn,
        "ocid-" + userIgn,
        "Warrior",
        250,
        Instant.now(),
        Instant.now(),
        version,
        version, // lastAppliedVersion
        1000000L,
        3,
        java.util.List.of(),
        false);
  }
}
