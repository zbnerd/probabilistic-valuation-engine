package maple.expectation.application.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationQueue;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationTask;
import maple.expectation.core.port.inbound.CalculationQueuePort;
import maple.expectation.core.port.inbound.TaskReceipt;
import org.springframework.stereotype.Component;

/**
 * CalculationQueuePort 구현체 (ADR-005, ADR-355)
 *
 * <p>책임: PriorityCalculationQueue에 위임
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalculationQueuePortAdapter implements CalculationQueuePort {

  private final ExpectationCalculationQueue queue;

  @Override
  public boolean offerHighPriority(String userIgn, boolean forceRecalculation) {
    ExpectationCalculationTask task =
        ExpectationCalculationTask.highPriority(userIgn, forceRecalculation);
    return queue.offer(task);
  }

  /**
   * HIGH priority task offer with receipt (ADR-355).
   *
   * <p>PGMQ messageId를 taskId로 반환.
   *
   * @param userIgn 캐릭터 IGN
   * @param forceRecalculation 강제 재계산 여부
   * @return TaskReceipt with taskId
   */
  public TaskReceipt offerHighPriorityWithReceipt(String userIgn, boolean forceRecalculation) {
    ExpectationCalculationTask task =
        ExpectationCalculationTask.highPriority(userIgn, forceRecalculation);
    return queue.offerWithReceipt(task);
  }
}
