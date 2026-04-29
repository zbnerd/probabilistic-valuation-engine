package maple.expectation.application.service.task;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.model.character.CharacterView;
import maple.expectation.core.port.inbound.CharacterViewQueryPort;
import maple.expectation.core.port.inbound.TaskStatus;
import maple.expectation.core.port.inbound.TaskStatusPort;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.pgmq.PgmqClient;
import maple.expectation.infrastructure.worker.ExpectationCalcLowWorker;
import maple.expectation.infrastructure.worker.ExpectationCalcWorker;
import org.springframework.stereotype.Service;

/**
 * Task 상태 조회 서비스 (ADR-355)
 *
 * <p>PostgreSQL CharacterView을 source of truth로 사용. 조회 순서:
 *
 * <ol>
 *   <li>PostgreSQL CharacterView → 존재 → COMPLETED
 *   <li>PGMQ archive → 존재 → COMPLETED (보조)
 *   <li>기타 → PENDING
 * </ol>
 */
@Slf4j
@Service
public class TaskStatusService implements TaskStatusPort {

  private final CharacterViewQueryPort queryPort;
  private final PgmqClient pgmqClient;
  private final LogicExecutor executor;

  public TaskStatusService(
      CharacterViewQueryPort queryPort, PgmqClient pgmqClient, LogicExecutor executor) {
    this.queryPort = queryPort;
    this.pgmqClient = pgmqClient;
    this.executor = executor;
  }

  @Override
  public TaskStatus getStatus(String userIgn, String taskId) {
    TaskContext context = TaskContext.of("TaskStatus", "GetStatus", userIgn);

    return executor.executeOrDefault(
        () -> resolveStatus(userIgn, taskId), TaskStatus.NOT_FOUND, context);
  }

  private TaskStatus resolveStatus(String userIgn, String taskId) {
    long messageId = parseMessageId(taskId);
    if (messageId <= 0) {
      return TaskStatus.NOT_FOUND;
    }

    // 1. PostgreSQL (source of truth)
    Optional<CharacterView> cached = queryPort.findByUserIgn(userIgn);
    if (cached.filter(view -> taskId.equals(view.getMessageId())).isPresent()) {
      return TaskStatus.COMPLETED;
    }

    // 2. PGMQ archive check (보조)
    if (isArchivedInAnyQueue(messageId)) {
      return TaskStatus.COMPLETED;
    }

    // 3. 활성 큐에서 read_ct 확인 → PROCESSING 판별
    int readCount = getMaxReadCount(messageId);
    if (readCount > 0) {
      return TaskStatus.PROCESSING;
    }

    // 4. Task record deleted and no signal in any queue or archive.
    // Return NOT_FOUND (terminal) instead of PENDING to prevent infinite polling
    // when the task record has been cleaned up.
    return TaskStatus.NOT_FOUND;
  }

  private boolean isArchivedInAnyQueue(long messageId) {
    return pgmqClient.isArchived(ExpectationCalcWorker.QUEUE_NAME, messageId)
        || pgmqClient.isArchived(ExpectationCalcLowWorker.QUEUE_NAME, messageId);
  }

  private int getMaxReadCount(long messageId) {
    return Math.max(
        pgmqClient.getMessageReadCount(ExpectationCalcWorker.QUEUE_NAME, messageId),
        pgmqClient.getMessageReadCount(ExpectationCalcLowWorker.QUEUE_NAME, messageId));
  }

  private long parseMessageId(String taskId) {
    return executor.executeOrDefault(
        () -> Long.parseLong(taskId), -1L, TaskContext.of("TaskStatus", "ParseId", taskId));
  }
}
