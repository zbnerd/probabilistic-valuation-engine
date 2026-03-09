package maple.expectation.application.scheduler;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.worker.MongoDBSyncWorker;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis Streams PEL Janitor Scheduler (Unit 2: PEL Zombie Fix)
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Periodically claim orphaned messages from crashed consumers using XAUTOCLAIM
 *   <li>Clean up PEL (Pending Entries List) to prevent memory leaks
 *   <li>Emit metrics for PEL cleanup operations
 * </ul>
 *
 * <h3>Background: The PEL Zombie Problem</h3>
 *
 * When a consumer crashes without acknowledging messages, those messages remain in the PEL
 * indefinitely. This causes:
 *
 * <ul>
 *   <li><b>Memory leaks:</b> Unbounded PEL growth consumes Redis memory
 *   <li><b>Data loss:</b> Orphaned messages are never reprocessed
 *   <li><b>Stalled processing:</b> New messages are delivered while old ones sit idle
 * </ul>
 *
 * <h3>Solution: XAUTOCLAIM Janitor</h3>
 *
 * This scheduler uses Redis XAUTOCLAIM to:
 *
 * <ol>
 *   <li>Claim messages idle for > 5 minutes (configurable)
 *   <li>Process claimed messages through the normal flow
 *   <li>Move poisoned pills to DLQ after max retries
 * </ol>
 *
 * <h3>Section 12 Compliance (Zero Try-Catch):</h3>
 *
 * All exception handling delegated to LogicExecutor.
 *
 * @see MongoDBSyncWorker#claimOrphanedMessages(Duration)
 * @see <a href="https://redis.io/commands/xautoclaim/">XAUTOCLAIM</a>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class StreamJanitorScheduler {

  private static final Duration MIN_IDLE_TIME = Duration.ofMinutes(5);

  private final MongoDBSyncWorker mongoDBSyncWorker;
  private final LogicExecutor executor;

  public StreamJanitorScheduler(MongoDBSyncWorker mongoDBSyncWorker, LogicExecutor executor) {
    this.mongoDBSyncWorker = mongoDBSyncWorker;
    this.executor = executor;
  }

  /**
   * PEL Janitor: Claim orphaned messages using XAUTOCLAIM.
   *
   * <p>Runs every 5 minutes to reclaim messages from inactive consumers.
   *
   * <h4>Execution Flow</h4>
   *
   * <ol>
   *   <li>Call XAUTOCLAIM with minIdleTime=5 minutes
   *   <li>Process claimed messages through MongoDBSyncWorker
   *   <li>Emit metrics for claimed messages
   * </ol>
   *
   * <h4>Conflict Prevention</h4>
   *
   * The worker's PEL recovery (Phase 1) runs on startup using 1-second idle time. The janitor uses
   * 5-minute idle time to avoid claiming messages that the active worker is still processing.
   */
  @Scheduled(fixedRateString = "PT5M")
  public void claimOrphanedMessages() {
    executor.executeOrCatch(
        () -> {
          log.debug("[StreamJanitor] Starting XAUTOCLAIM for orphaned PEL cleanup");
          int claimedCount = mongoDBSyncWorker.claimOrphanedMessages(MIN_IDLE_TIME);

          if (claimedCount > 0) {
            log.info("[StreamJanitor] Claimed {} orphaned messages from PEL", claimedCount);
          }
          return null;
        },
        e -> {
          log.error("[StreamJanitor] Failed to claim orphaned messages", e);
          return null;
        },
        TaskContext.of("StreamJanitor", "ClaimOrphanedMessages"));
  }
}
