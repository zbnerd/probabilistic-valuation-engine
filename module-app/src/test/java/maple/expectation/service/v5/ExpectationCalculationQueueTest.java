package maple.expectation.service.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationQueue;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationTask;
import maple.expectation.application.service.expectation.queue.QueuePriority;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.core.model.job.CalculationJob;
import maple.expectation.core.model.job.CalculationJobStatus;
import maple.expectation.core.port.inbound.TaskReceipt;
import maple.expectation.core.port.out.CalculationJobPort;
import maple.expectation.core.port.out.PgmqPort;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("V5: Direct Dispatch Queue Tests")
class ExpectationCalculationQueueTest {

  private LogicExecutor executor;
  private PgmqPort pgmqPort;
  private CalculationJobPort jobPort;
  private ExpectationCalculationQueue queue;

  @BeforeEach
  void setUp() {
    executor = new TestLogicExecutor();
    pgmqPort = mock(PgmqPort.class);
    jobPort = mock(CalculationJobPort.class);
    queue = new ExpectationCalculationQueue(pgmqPort, jobPort, executor);
  }

  @Test
  @DisplayName("offer() creates job and dispatches to external_api_queue")
  void offerCreatesJobAndDispatches() {
    UUID jobId = UUID.randomUUID();
    CalculationJob job = mockJob(jobId, CalculationJobStatus.REQUESTED);
    when(jobPort.createJob(null, "user1", 1)).thenReturn(job);
    when(jobPort.transitionStatus(
            jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING))
        .thenReturn(true);
    when(pgmqPort.send(eq("external_api_queue"), any())).thenReturn(1L);

    ExpectationCalculationTask highTask = ExpectationCalculationTask.highPriority("user1", false);
    assertThat(queue.offer(highTask)).isTrue();

    verify(jobPort).createJob(null, "user1", 1);
    verify(pgmqPort).send(eq("external_api_queue"), any());
  }

  @Test
  @DisplayName("existing active job is returned without re-dispatching")
  void existingActiveJobReturnedWithoutDispatch() {
    UUID jobId = UUID.randomUUID();
    CalculationJob job = mockJob(jobId, CalculationJobStatus.OCID_RESOLVING);
    when(jobPort.createJob(null, "user1", 1)).thenReturn(job);

    ExpectationCalculationTask task = ExpectationCalculationTask.highPriority("user1", false);
    assertThat(queue.offer(task)).isTrue();

    verify(jobPort).createJob(null, "user1", 1);
    verify(jobPort, never()).transitionStatus(any(), any(), any());
    verify(pgmqPort, never()).send(anyString(), any());
  }

  @Test
  @DisplayName("offerWithReceipt returns job ID as taskId")
  void offerWithReceiptReturnsJobId() {
    UUID jobId = UUID.randomUUID();
    CalculationJob job = mockJob(jobId, CalculationJobStatus.REQUESTED);
    when(jobPort.createJob(null, "user1", 1)).thenReturn(job);
    when(jobPort.transitionStatus(
            jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING))
        .thenReturn(true);
    when(pgmqPort.send(eq("external_api_queue"), any())).thenReturn(1L);

    TaskReceipt receipt =
        queue.offerWithReceipt(ExpectationCalculationTask.highPriority("user1", false));

    assertThat(receipt.getQueued()).isTrue();
    assertThat(receipt.getTaskId()).isEqualTo(jobId.toString());
  }

  @Test
  @DisplayName("addHighPriorityTask convenience method works")
  void addHighPriorityTaskWorks() {
    UUID jobId = UUID.randomUUID();
    CalculationJob job = mockJob(jobId, CalculationJobStatus.REQUESTED);
    when(jobPort.createJob(null, "user1", 1)).thenReturn(job);
    when(jobPort.transitionStatus(
            jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING))
        .thenReturn(true);
    when(pgmqPort.send(anyString(), any())).thenReturn(1L);

    assertThat(queue.addHighPriorityTask("user1", true)).isTrue();
  }

  @Test
  @DisplayName("addLowPriorityTask convenience method works")
  void addLowPriorityTaskWorks() {
    UUID jobId = UUID.randomUUID();
    CalculationJob job = mockJob(jobId, CalculationJobStatus.REQUESTED);
    when(jobPort.createJob(null, "user1", 1)).thenReturn(job);
    when(jobPort.transitionStatus(
            jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING))
        .thenReturn(true);
    when(pgmqPort.send(anyString(), any())).thenReturn(1L);

    assertThat(queue.addLowPriorityTask("user1")).isTrue();
  }

  @Test
  @DisplayName("poll() throws UnsupportedOperationException")
  void pollThrowsUnsupportedOperation() {
    assertThatThrownBy(() -> queue.poll(QueuePriority.HIGH))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("complete() is no-op")
  void completeIsNoOp() {
    ExpectationCalculationTask task = ExpectationCalculationTask.highPriority("user1", false);
    queue.complete(task);
  }

  @Test
  @DisplayName("size() returns 0 (deprecated)")
  void sizeReturnsZero() {
    assertThat(queue.size()).isEqualTo(0);
  }

  @Test
  @DisplayName("forceRecalculation flag preserved")
  void forceRecalculationFlagPreserved() {
    ExpectationCalculationTask task1 = ExpectationCalculationTask.highPriority("user1", true);
    ExpectationCalculationTask task2 = ExpectationCalculationTask.highPriority("user2", false);
    ExpectationCalculationTask task3 = ExpectationCalculationTask.lowPriority("user3");

    assertThat(task1.isForceRecalculation()).isTrue();
    assertThat(task2.isForceRecalculation()).isFalse();
    assertThat(task3.isForceRecalculation()).isFalse();
  }

  @Test
  @DisplayName("UUID-based taskId generation")
  void taskIdIsUUID() {
    ExpectationCalculationTask task1 = ExpectationCalculationTask.highPriority("user1", false);
    ExpectationCalculationTask task2 = ExpectationCalculationTask.highPriority("user1", false);

    assertThat(task1.getTaskId()).isNotNull();
    assertThat(task2.getTaskId()).isNotNull();
    assertThat(task1.getTaskId()).isNotEqualTo(task2.getTaskId());
  }

  @Test
  @DisplayName("Priority ordering: HIGH(0) < LOW(1)")
  void priorityOrdering() {
    ExpectationCalculationTask highTask = ExpectationCalculationTask.highPriority("user1", false);
    ExpectationCalculationTask lowTask = ExpectationCalculationTask.lowPriority("user2");

    assertThat(highTask.getPriority().ordinal()).isLessThan(lowTask.getPriority().ordinal());
    assertThat(highTask.getPriority()).isEqualTo(QueuePriority.HIGH);
    assertThat(lowTask.getPriority()).isEqualTo(QueuePriority.LOW);
  }

  private CalculationJob mockJob(UUID jobId, CalculationJobStatus status) {
    CalculationJob job = mock(CalculationJob.class);
    when(job.getJobId()).thenReturn(jobId);
    when(job.getStatus()).thenReturn(status);
    when(job.getUserIgn()).thenReturn("user1");
    when(job.getPresetNo()).thenReturn(1);
    return job;
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
