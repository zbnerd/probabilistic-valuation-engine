package maple.expectation.application.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationQueue;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationTask;
import maple.expectation.core.port.inbound.CalculationQueuePort;
import org.springframework.stereotype.Component;

/**
 * CalculationQueuePort 구현체 (ADR-005)
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
}
