package maple.expectation.service.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import maple.expectation.application.service.task.TaskStatusService;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.core.model.job.CalculationJob;
import maple.expectation.core.model.job.CalculationJobStatus;
import maple.expectation.core.port.inbound.TaskStatus;
import maple.expectation.core.port.out.CalculationJobPort;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("TaskStatusService")
class TaskStatusServiceTest {

  private CalculationJobPort jobPort;
  private TaskStatusService service;

  @BeforeEach
  void setUp() {
    jobPort = mock(CalculationJobPort.class);
    service = new TaskStatusService(jobPort, new TestLogicExecutor());
  }

  @Test
  @DisplayName("COMPLETED job returns COMPLETED status")
  void completedJobReturnsCompleted() {
    UUID jobId = UUID.randomUUID();
    CalculationJob job = mock(CalculationJob.class);
    when(job.getUserIgn()).thenReturn("user1");
    when(job.getStatus()).thenReturn(CalculationJobStatus.COMPLETED);
    when(jobPort.findJobById(jobId)).thenReturn(job);

    TaskStatus status = service.getStatus("user1", jobId.toString());

    assertThat(status).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  @DisplayName("OCID_RESOLVING job returns PROCESSING status")
  void ocidResolvingJobReturnsProcessing() {
    UUID jobId = UUID.randomUUID();
    CalculationJob job = mock(CalculationJob.class);
    when(job.getUserIgn()).thenReturn("user1");
    when(job.getStatus()).thenReturn(CalculationJobStatus.OCID_RESOLVING);
    when(jobPort.findJobById(jobId)).thenReturn(job);

    TaskStatus status = service.getStatus("user1", jobId.toString());

    assertThat(status).isEqualTo(TaskStatus.PROCESSING);
  }

  @Test
  @DisplayName("non-existent job returns NOT_FOUND")
  void nonExistentJobReturnsNotFound() {
    UUID jobId = UUID.randomUUID();
    when(jobPort.findJobById(jobId)).thenReturn(null);

    TaskStatus status = service.getStatus("user1", jobId.toString());

    assertThat(status).isEqualTo(TaskStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("wrong userIgn returns NOT_FOUND")
  void wrongUserIgnReturnsNotFound() {
    UUID jobId = UUID.randomUUID();
    CalculationJob job = mock(CalculationJob.class);
    when(job.getUserIgn()).thenReturn("otherUser");
    when(job.getStatus()).thenReturn(CalculationJobStatus.COMPLETED);
    when(jobPort.findJobById(jobId)).thenReturn(job);

    TaskStatus status = service.getStatus("user1", jobId.toString());

    assertThat(status).isEqualTo(TaskStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("invalid taskId returns NOT_FOUND")
  void invalidTaskIdReturnsNotFound() {
    TaskStatus status = service.getStatus("user1", "not-a-uuid");

    assertThat(status).isEqualTo(TaskStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("FAILED job returns NOT_FOUND")
  void failedJobReturnsNotFound() {
    UUID jobId = UUID.randomUUID();
    CalculationJob job = mock(CalculationJob.class);
    when(job.getUserIgn()).thenReturn("user1");
    when(job.getStatus()).thenReturn(CalculationJobStatus.FAILED);
    when(jobPort.findJobById(jobId)).thenReturn(job);

    TaskStatus status = service.getStatus("user1", jobId.toString());

    assertThat(status).isEqualTo(TaskStatus.NOT_FOUND);
  }

  private static class TestLogicExecutor implements LogicExecutor {
    @Override
    public <T> T execute(ThrowingSupplier<T> task, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public <T> T execute(ThrowingSupplier<T> task, String taskName) {
      return execute(task, TaskContext.of("Legacy", taskName));
    }

    @Override
    public <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        return defaultValue;
      }
    }

    @Override
    public void executeVoid(ThrowingRunnable task, TaskContext context) {
      try {
        task.run();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void executeVoid(ThrowingRunnable task, String taskName) {
      executeVoid(task, TaskContext.of("Legacy", taskName));
    }

    @Override
    public <T> T executeWithFinally(
        ThrowingSupplier<T> task, Runnable finallyBlock, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      } finally {
        finallyBlock.run();
      }
    }

    @Override
    public <T> T executeWithTranslation(
        ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        throw translator.translate(e, context);
      }
    }

    @Override
    public <T> T executeWithFallback(
        ThrowingSupplier<T> task,
        kotlin.jvm.functions.Function1<? super Throwable, ? extends T> fallback,
        TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        return fallback.invoke(e);
      }
    }

    @Override
    public <T> T executeWithFallback(
        ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        @SuppressWarnings("unchecked")
        T result = (T) translator.translate(e, context);
        return result;
      }
    }

    @Override
    public <T> T executeOrCatch(
        ThrowingSupplier<T> task,
        kotlin.jvm.functions.Function1<? super Throwable, ? extends T> recovery,
        TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        return recovery.invoke(e);
      }
    }

    @Override
    public <T> T executeOrCatch(
        ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        @SuppressWarnings("unchecked")
        T result = (T) translator.translate(e, context);
        return result;
      }
    }

    @Override
    public void executeVoidJava(Runnable task, TaskContext context) {
      task.run();
    }

    @Override
    public void executeVoidJava(Runnable task, String taskName) {
      executeVoidJava(task, TaskContext.of("Legacy", taskName));
    }
  }
}
