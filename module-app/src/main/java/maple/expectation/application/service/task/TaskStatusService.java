package maple.expectation.application.service.task;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.model.job.CalculationJob;
import maple.expectation.core.model.job.CalculationJobStatus;
import maple.expectation.core.port.inbound.TaskStatus;
import maple.expectation.core.port.inbound.TaskStatusPort;
import maple.expectation.core.port.out.CalculationJobPort;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Service;

/**
 * Task 상태 조회 서비스 (ADR-355)
 *
 * <p>Job UUID 기반 상태 조회. Controller에서 직접 job을 생성하므로 PGMQ messageId 대신 jobId를 taskId로 사용.
 */
@Slf4j
@Service
public class TaskStatusService implements TaskStatusPort {

  private final CalculationJobPort jobPort;
  private final LogicExecutor executor;

  public TaskStatusService(CalculationJobPort jobPort, LogicExecutor executor) {
    this.jobPort = jobPort;
    this.executor = executor;
  }

  @Override
  public TaskStatus getStatus(String userIgn, String taskId) {
    TaskContext context = TaskContext.of("TaskStatus", "GetStatus", userIgn);

    return executor.executeOrDefault(
        () -> resolveStatus(userIgn, taskId), TaskStatus.NOT_FOUND, context);
  }

  private TaskStatus resolveStatus(String userIgn, String taskId) {
    UUID jobId = parseJobId(taskId);
    if (jobId == null) {
      return TaskStatus.NOT_FOUND;
    }

    CalculationJob job = jobPort.findJobById(jobId);
    if (job == null || !job.getUserIgn().equals(userIgn)) {
      return TaskStatus.NOT_FOUND;
    }

    return mapStatus(job.getStatus());
  }

  private TaskStatus mapStatus(CalculationJobStatus status) {
    return switch (status) {
      case REQUESTED, OCID_RESOLVING, API_REQUESTED, SNAPSHOT_READY, CALCULATING, RETRYING ->
          TaskStatus.PROCESSING;
      case COMPLETED -> TaskStatus.COMPLETED;
      case FAILED -> TaskStatus.NOT_FOUND;
    };
  }

  private UUID parseJobId(String taskId) {
    return executor.executeOrDefault(
        () -> UUID.fromString(taskId), null, TaskContext.of("TaskStatus", "ParseId", taskId));
  }
}
