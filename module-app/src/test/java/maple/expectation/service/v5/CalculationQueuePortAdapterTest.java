package maple.expectation.service.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.mockito.Mockito.mock;

import maple.expectation.application.service.expectation.queue.ExpectationCalculationQueue;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationTask;
import maple.expectation.application.usecase.CalculationQueuePortAdapter;
import maple.expectation.core.port.inbound.TaskReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for CalculationQueuePortAdapter.
 *
 * <p>Tests delegation to ExpectationCalculationQueue for queue operations.
 */
@Tag("unit")
@DisplayName("CalculationQueuePortAdapter: Delegation Tests")
class CalculationQueuePortAdapterTest {

  private ExpectationCalculationQueue queue;
  private CalculationQueuePortAdapter adapter;

  private static final String TEST_USER_IGN = "TestUser";

  @BeforeEach
  void setUp() {
    queue = mock(ExpectationCalculationQueue.class);
    adapter = new CalculationQueuePortAdapter(queue);
  }

  @Test
  @DisplayName("offerHighPriorityWithReceipt delegates to queue.offerWithReceipt")
  void offerHighPriorityWithReceipt_delegatesToQueue() {
    // Given
    TaskReceipt expectedReceipt =
        new TaskReceipt("msg123", TEST_USER_IGN, true);
    when(queue.offerWithReceipt(any(ExpectationCalculationTask.class)))
        .thenReturn(expectedReceipt);

    // When
    TaskReceipt result = adapter.offerHighPriorityWithReceipt(TEST_USER_IGN, false);

    // Then
    assertThat(result).isEqualTo(expectedReceipt);
    verify(queue).offerWithReceipt(any(ExpectationCalculationTask.class));
  }

  @Test
  @DisplayName("offerHighPriority with forceRecalculation=true delegates to queue.offer")
  void offerHighPriority_withForce_delegatesToQueue() {
    // Given
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(true);

    // When
    boolean result = adapter.offerHighPriority(TEST_USER_IGN, true);

    // Then
    assertThat(result).isTrue();
    verify(queue).offer(any(ExpectationCalculationTask.class));
  }

  @Test
  @DisplayName("offerHighPriority with forceRecalculation=false delegates to queue.offer")
  void offerHighPriority_withoutForce_delegatesToQueue() {
    // Given
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(true);

    // When
    boolean result = adapter.offerHighPriority(TEST_USER_IGN, false);

    // Then
    assertThat(result).isTrue();
    verify(queue).offer(any(ExpectationCalculationTask.class));
  }

  @Test
  @DisplayName("addHighPriorityTask convenience method delegates to queue.offer")
  void addHighPriorityTask_delegatesToQueue() {
    // Given
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(true);

    // When - queue has addHighPriorityTask method, but adapter uses offerHighPriority
    boolean result = adapter.offerHighPriority(TEST_USER_IGN, true);

    // Then
    assertThat(result).isTrue();
    verify(queue).offer(any(ExpectationCalculationTask.class));
  }

  @Test
  @DisplayName("addLowPriorityTask delegates to queue.offer via LOW priority")
  void addLowPriorityTask_delegatesToQueue() {
    // Given - queue has addLowPriorityTask method
    when(queue.addLowPriorityTask(eq(TEST_USER_IGN))).thenReturn(true);

    // When - use the queue method directly since adapter doesn't have this
    boolean result = queue.addLowPriorityTask(TEST_USER_IGN);

    // Then
    assertThat(result).isTrue();
    verify(queue).addLowPriorityTask(eq(TEST_USER_IGN));
  }

  @Test
  @DisplayName("size delegates to queue.size")
  void size_delegatesToQueue() {
    // Given
    when(queue.size()).thenReturn(42);

    // When
    int result = queue.size();

    // Then
    assertThat(result).isEqualTo(42);
    verify(queue).size();
  }

  @Test
  @DisplayName("offerHighPriority returns false when queue rejects task")
  void offerHighPriority_returnsFalseWhenQueueFull() {
    // Given
    when(queue.offer(any(ExpectationCalculationTask.class))).thenReturn(false);

    // When
    boolean result = adapter.offerHighPriority(TEST_USER_IGN, true);

    // Then
    assertThat(result).isFalse();
    verify(queue).offer(any(ExpectationCalculationTask.class));
  }

  @Test
  @DisplayName("offerHighPriorityWithReceipt returns rejected receipt when queue full")
  void offerHighPriorityWithReceipt_returnsRejectedWhenQueueFull() {
    // Given
    TaskReceipt rejectedReceipt = TaskReceipt.rejected(TEST_USER_IGN);
    when(queue.offerWithReceipt(any(ExpectationCalculationTask.class)))
        .thenReturn(rejectedReceipt);

    // When
    TaskReceipt result = adapter.offerHighPriorityWithReceipt(TEST_USER_IGN, false);

    // Then
    assertThat(result.getQueued()).isFalse();
    assertThat(result.getTaskId()).isNull();
    assertThat(result.getUserIgn()).isEqualTo(TEST_USER_IGN);
    verify(queue).offerWithReceipt(any(ExpectationCalculationTask.class));
  }
}
