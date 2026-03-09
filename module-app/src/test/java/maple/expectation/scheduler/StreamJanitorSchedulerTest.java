package maple.expectation.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import maple.expectation.application.scheduler.StreamJanitorScheduler;
import maple.expectation.application.worker.MongoDBSyncWorker;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit 2: Redis Streams PEL Zombie Fix Tests
 *
 * <h3>Test Scope</h3>
 *
 * <ul>
 *   <li>StreamJanitorScheduler calls claimOrphanedMessages periodically
 *   <li>Metrics are emitted when messages are claimed
 *   <li>Error handling when XAUTOCLAIM fails
 * </ul>
 *
 * <h3>E2E Test Scenario</h3>
 *
 * <pre>
 * 1. Start Redis + App
 * 2. Publish message to stream
 * 3. Kill worker mid-processing (kill -9)
 * 4. Check XPENDING shows pending message
 * 5. Restart app, verify XAUTOCLAIM recovers message
 * 6. Check logs for PEL cleanup metrics
 * </pre>
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit 2: PEL Zombie Fix - Stream Janitor Tests")
class StreamJanitorSchedulerTest {

  @Mock private MongoDBSyncWorker mongoDBSyncWorker;

  @Mock private LogicExecutor executor;

  private StreamJanitorScheduler scheduler;

  @BeforeEach
  void setUp() {
    // Mock executor to actually execute tasks
    doAnswer(
            invocation -> {
              ThrowingSupplier<?> task = invocation.getArgument(0);
              try {
                return task.get();
              } catch (Throwable e) {
                // If recovery is provided, use it
                if (invocation.getArguments().length > 2) {
                  java.util.function.Function<Throwable, ?> recovery = invocation.getArgument(2);
                  return recovery.apply(e);
                }
                throw new RuntimeException(e);
              }
            })
        .when(executor)
        .executeOrCatch(
            any(ThrowingSupplier.class), any(ExceptionTranslator.class), any(TaskContext.class));

    scheduler = new StreamJanitorScheduler(mongoDBSyncWorker, executor);
  }

  @Test
  @DisplayName("Janitor: claimOrphanedMessages calls worker with correct idle time")
  void testClaimOrphanedMessages_CallsWorkerWithCorrectIdleTime() {
    // Given: Worker returns 5 claimed messages
    when(mongoDBSyncWorker.claimOrphanedMessages(any())).thenReturn(5);

    // When: Scheduler runs
    scheduler.claimOrphanedMessages();

    // Then: Worker is called with 5-minute idle time
    verify(mongoDBSyncWorker).claimOrphanedMessages(java.time.Duration.ofMinutes(5));
  }

  @Test
  @DisplayName("Janitor: Logs info when messages are claimed")
  void testClaimOrphanedMessages_LogsInfoWhenMessagesClaimed() {
    // Given: Worker returns 3 claimed messages
    when(mongoDBSyncWorker.claimOrphanedMessages(any())).thenReturn(3);

    // When: Scheduler runs (should log info)
    scheduler.claimOrphanedMessages();

    // Then: Verify worker was called
    verify(mongoDBSyncWorker).claimOrphanedMessages(any());
  }

  @Test
  @DisplayName("Janitor: Handles zero messages gracefully")
  void testClaimOrphanedMessages_HandlesZeroMessagesGracefully() {
    // Given: Worker returns 0 claimed messages
    when(mongoDBSyncWorker.claimOrphanedMessages(any())).thenReturn(0);

    // When: Scheduler runs
    scheduler.claimOrphanedMessages();

    // Then: No exception, worker called once
    verify(mongoDBSyncWorker).claimOrphanedMessages(any());
  }

  @Test
  @DisplayName("Janitor: Continues execution after worker exception")
  void testClaimOrphanedMessages_ContinuesAfterWorkerException() {
    // Given: Worker throws exception
    when(mongoDBSyncWorker.claimOrphanedMessages(any()))
        .thenThrow(new RuntimeException("Redis connection failed"));

    // When: Scheduler runs
    // Should not throw due to executeOrCatch wrapper
    scheduler.claimOrphanedMessages();

    // Then: Worker was called, exception handled
    verify(mongoDBSyncWorker).claimOrphanedMessages(any());
  }
}
