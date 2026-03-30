package maple.expectation.service.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationQueue;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationTask;
import maple.expectation.application.service.expectation.queue.QueuePriority;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.CheckedRunnable;
import maple.expectation.infrastructure.executor.function.CheckedSupplier;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import maple.expectation.infrastructure.pgmq.PgmqClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("V5 CQRS: Priority Queue Tests (PGMQ)")
class ExpectationCalculationQueueTest {

  private LogicExecutor executor;
  private CheckedLogicExecutor checkedExecutor;
  private PgmqClient pgmqClient;
  private ExpectationCalculationQueue queue;

  @BeforeEach
  void setUp() {
    executor = new TestLogicExecutor();
    checkedExecutor = new TestCheckedLogicExecutor();
    pgmqClient = mock(PgmqClient.class);
    queue = new ExpectationCalculationQueue(pgmqClient, executor);
  }

  @Test
  @DisplayName("offer()는 PGMQ 큐에 메시지를 발행함")
  void offerSendsToPgmq() {
    when(pgmqClient.queueLength(anyString())).thenReturn(0L);
    when(pgmqClient.send(anyString(), any())).thenReturn(1L);

    ExpectationCalculationTask highTask = ExpectationCalculationTask.highPriority("user1", false);
    assertThat(queue.offer(highTask)).isTrue();
  }

  @Test
  @DisplayName("HIGH 우선순위와 LOW 우선순위 모두 offer 가능")
  void offerBothPriorities() {
    when(pgmqClient.queueLength(anyString())).thenReturn(0L);
    when(pgmqClient.send(anyString(), any())).thenReturn(1L);

    ExpectationCalculationTask highTask = ExpectationCalculationTask.highPriority("user1", false);
    ExpectationCalculationTask lowTask = ExpectationCalculationTask.lowPriority("user2");

    assertThat(queue.offer(highTask)).isTrue();
    assertThat(queue.offer(lowTask)).isTrue();
  }

  @Test
  @DisplayName("큐가 가득 차면 백프레셔로 reject")
  void backpressureWhenQueueFull() {
    when(pgmqClient.queueLength(anyString())).thenReturn(1000L);

    ExpectationCalculationTask task = ExpectationCalculationTask.highPriority("user1", false);
    boolean accepted = queue.offer(task);

    assertThat(accepted).isFalse();
  }

  @Test
  @DisplayName("큐가 가득 차지 않으면 수락")
  void acceptedWhenQueueNotFull() {
    when(pgmqClient.queueLength(anyString())).thenReturn(500L);
    when(pgmqClient.send(anyString(), any())).thenReturn(1L);

    ExpectationCalculationTask task = ExpectationCalculationTask.lowPriority("user1");
    boolean accepted = queue.offer(task);

    assertThat(accepted).isTrue();
  }

  @Test
  @DisplayName("poll()은 UnsupportedOperationException 발생")
  void pollThrowsUnsupportedOperation() {
    assertThatThrownBy(() -> queue.poll(QueuePriority.HIGH))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("poll(timeout)은 UnsupportedOperationException 발생")
  void pollWithTimeoutThrowsUnsupportedOperation() {
    assertThatThrownBy(() -> queue.poll(QueuePriority.HIGH, 100))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("complete()은 no-op으로 동작 (예외 없음)")
  void completeIsNoOp() {
    ExpectationCalculationTask task = ExpectationCalculationTask.highPriority("user1", false);
    // Should not throw
    queue.complete(task);
  }

  @Test
  @DisplayName("addHighPriorityTask 편의 메서드 동작")
  void addHighPriorityTaskConvenienceMethod() {
    when(pgmqClient.queueLength(anyString())).thenReturn(0L);
    when(pgmqClient.send(anyString(), any())).thenReturn(1L);

    boolean added = queue.addHighPriorityTask("user1", true);

    assertThat(added).isTrue();
  }

  @Test
  @DisplayName("addLowPriorityTask 편의 메서드 동작")
  void addLowPriorityTaskConvenienceMethod() {
    when(pgmqClient.queueLength(anyString())).thenReturn(0L);
    when(pgmqClient.send(anyString(), any())).thenReturn(1L);

    boolean added = queue.addLowPriorityTask("user1");

    assertThat(added).isTrue();
  }

  @Test
  @DisplayName("size()는 PGMQ 큐 길이 합 반환")
  void sizeReturnsPgmqQueueLengthSum() {
    when(pgmqClient.queueLength("expectation_calc_high")).thenReturn(5L);
    when(pgmqClient.queueLength("expectation_calc_low")).thenReturn(3L);

    assertThat(queue.size()).isEqualTo(8);
  }

  @Test
  @DisplayName("getHighPriorityCount()는 HIGH 큐 길이 반환")
  void highPriorityCountReturnsHighQueueLength() {
    when(pgmqClient.queueLength("expectation_calc_high")).thenReturn(5L);

    assertThat(queue.getHighPriorityCount()).isEqualTo(5);
  }

  @Test
  @DisplayName("getLowPriorityCount()는 LOW 큐 길이 반환")
  void lowPriorityCountReturnsLowQueueLength() {
    when(pgmqClient.queueLength("expectation_calc_low")).thenReturn(3L);

    assertThat(queue.getLowPriorityCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("forceRecalculation 플래그 유지")
  void forceRecalculationFlagPreserved() {
    ExpectationCalculationTask task1 = ExpectationCalculationTask.highPriority("user1", true);
    ExpectationCalculationTask task2 = ExpectationCalculationTask.highPriority("user2", false);
    ExpectationCalculationTask task3 = ExpectationCalculationTask.lowPriority("user3");

    assertThat(task1.isForceRecalculation()).isTrue();
    assertThat(task2.isForceRecalculation()).isFalse();
    assertThat(task3.isForceRecalculation()).isFalse();
  }

  @Test
  @DisplayName("작업 생성 시간 설정 확인")
  void taskCreatedAtSet() {
    Instant beforeCreation = Instant.now();
    ExpectationCalculationTask task = ExpectationCalculationTask.highPriority("user1", false);
    Instant afterCreation = Instant.now();

    assertThat(task.getCreatedAt()).isNotNull();
    assertThat(task.getCreatedAt()).isBetween(beforeCreation, afterCreation);
  }

  @Test
  @DisplayName("UUID 기반 taskId 생성 확인")
  void taskIdIsUUID() {
    ExpectationCalculationTask task1 = ExpectationCalculationTask.highPriority("user1", false);
    ExpectationCalculationTask task2 = ExpectationCalculationTask.highPriority("user1", false);

    assertThat(task1.getTaskId()).isNotNull();
    assertThat(task2.getTaskId()).isNotNull();
    assertThat(task1.getTaskId()).isNotEqualTo(task2.getTaskId());
  }

  @Test
  @DisplayName("Priority 순서: HIGH(0) < LOW(1)")
  void priorityOrdering() {
    ExpectationCalculationTask highTask = ExpectationCalculationTask.highPriority("user1", false);
    ExpectationCalculationTask lowTask = ExpectationCalculationTask.lowPriority("user2");

    assertThat(highTask.getPriority().ordinal()).isLessThan(lowTask.getPriority().ordinal());
    assertThat(highTask.getPriority()).isEqualTo(QueuePriority.HIGH);
    assertThat(lowTask.getPriority()).isEqualTo(QueuePriority.LOW);
  }

  /** 테스트용 간단한 LogicExecutor 구현 */
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

  /** 테스트용 간단한 CheckedLogicExecutor 구현 */
  private static class TestCheckedLogicExecutor implements CheckedLogicExecutor {
    @Override
    public <T> T execute(CheckedSupplier<T> task, TaskContext context) throws Exception {
      return task.get();
    }

    @Override
    public void executeVoid(CheckedRunnable task, TaskContext context) throws Exception {
      task.run();
    }

    @Override
    public <T> T executeUnchecked(
        CheckedSupplier<T> task,
        TaskContext context,
        java.util.function.Function<Exception, RuntimeException> mapper) {
      try {
        return task.get();
      } catch (Exception e) {
        throw mapper.apply(e);
      }
    }

    @Override
    public void executeUncheckedVoid(
        CheckedRunnable task,
        TaskContext context,
        java.util.function.Function<Exception, RuntimeException> mapper) {
      try {
        task.run();
      } catch (Exception e) {
        throw mapper.apply(e);
      }
    }

    @Override
    public <T> T executeWithFinallyUnchecked(
        CheckedSupplier<T> task,
        CheckedRunnable finalizer,
        TaskContext context,
        java.util.function.Function<Exception, RuntimeException> mapper) {
      try {
        return task.get();
      } catch (Exception e) {
        throw mapper.apply(e);
      } finally {
        try {
          finalizer.run();
        } catch (Exception e) {
          // ignore finalizer exception in test
        }
      }
    }

    @Override
    public void executeWithFinallyUncheckedVoid(
        CheckedRunnable task,
        CheckedRunnable finalizer,
        TaskContext context,
        java.util.function.Function<Exception, RuntimeException> mapper) {
      try {
        task.run();
      } catch (Exception e) {
        throw mapper.apply(e);
      } finally {
        try {
          finalizer.run();
        } catch (Exception e) {
          // ignore finalizer exception in test
        }
      }
    }
  }
}
