package maple.expectation.service.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import maple.expectation.application.service.task.TaskStatusService;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.core.domain.model.character.CharacterView;
import maple.expectation.core.port.inbound.CharacterViewQueryPort;
import maple.expectation.core.port.inbound.TaskStatus;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import maple.expectation.infrastructure.pgmq.PgmqClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("TaskStatusService")
class TaskStatusServiceTest {

  private CharacterViewQueryPort queryPort;
  private PgmqClient pgmqClient;
  private TaskStatusService service;

  @BeforeEach
  void setUp() {
    queryPort = mock(CharacterViewQueryPort.class);
    pgmqClient = mock(PgmqClient.class);
    service = new TaskStatusService(queryPort, pgmqClient, new TestLogicExecutor());
  }

  @Test
  @DisplayName("matching messageId view is COMPLETED")
  void matchingViewMessageIdReturnsCompleted() {
    CharacterView view = mock(CharacterView.class);
    when(view.getMessageId()).thenReturn("123");
    when(queryPort.findByUserIgn("user1")).thenReturn(Optional.of(view));

    TaskStatus status = service.getStatus("user1", "123");

    assertThat(status).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  @DisplayName("mismatched view messageId does not complete unrelated task")
  void mismatchedViewMessageIdDoesNotCompleteTask() {
    CharacterView view = mock(CharacterView.class);
    when(view.getMessageId()).thenReturn("999");
    when(queryPort.findByUserIgn("user1")).thenReturn(Optional.of(view));
    when(pgmqClient.isArchived("expectation_calc_high", 123L)).thenReturn(false);
    when(pgmqClient.isArchived("expectation_calc_low", 123L)).thenReturn(false);
    when(pgmqClient.getMessageReadCount("expectation_calc_high", 123L)).thenReturn(0);
    when(pgmqClient.getMessageReadCount("expectation_calc_low", 123L)).thenReturn(0);

    TaskStatus status = service.getStatus("user1", "123");

    assertThat(status).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  @DisplayName("archived task is COMPLETED even without current view row")
  void archivedTaskReturnsCompleted() {
    when(queryPort.findByUserIgn("user1")).thenReturn(Optional.empty());
    when(pgmqClient.isArchived("expectation_calc_high", 123L)).thenReturn(true);

    TaskStatus status = service.getStatus("user1", "123");

    assertThat(status).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  @DisplayName("read count marks task as PROCESSING")
  void readCountReturnsProcessing() {
    when(queryPort.findByUserIgn("user1")).thenReturn(Optional.empty());
    when(pgmqClient.isArchived("expectation_calc_high", 123L)).thenReturn(false);
    when(pgmqClient.isArchived("expectation_calc_low", 123L)).thenReturn(false);
    when(pgmqClient.getMessageReadCount("expectation_calc_high", 123L)).thenReturn(1);
    when(pgmqClient.getMessageReadCount("expectation_calc_low", 123L)).thenReturn(0);

    TaskStatus status = service.getStatus("user1", "123");

    assertThat(status).isEqualTo(TaskStatus.PROCESSING);
  }

  @Test
  @DisplayName("invalid taskId is NOT_FOUND")
  void invalidTaskIdReturnsNotFound() {
    TaskStatus status = service.getStatus("user1", "not-a-number");

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
