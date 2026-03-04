package maple.expectation.application.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.warmup.PopularCharacterTracker;
import maple.expectation.core.port.out.PopularCharacterTrackerPort;
import org.springframework.stereotype.Component;

/**
 * PopularCharacterTrackerPort 구현체 (ADR-005)
 *
 * <p>책임: PopularCharacterTracker에 위임
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PopularCharacterTrackerPortAdapter implements PopularCharacterTrackerPort {

  private final PopularCharacterTracker tracker;

  @Override
  public java.util.List<String> getYesterdayTopCharacters(int limit) {
    return tracker.getYesterdayTopCharacters(limit);
  }

  @Override
  public void recordAccess(String userIgn) {
    tracker.recordAccess(userIgn);
  }
}
