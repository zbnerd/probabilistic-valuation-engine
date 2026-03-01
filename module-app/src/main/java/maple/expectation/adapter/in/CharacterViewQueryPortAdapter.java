package maple.expectation.adapter.in;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.inbound.CharacterViewQueryPort;
import maple.expectation.infrastructure.mongodb.CharacterViewQueryService;
import org.springframework.stereotype.Component;

/**
 * CharacterViewQueryPort 구현체 (ADR-005)
 *
 * <p>책임: CharacterViewQueryService에 위임
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CharacterViewQueryPortAdapter implements CharacterViewQueryPort {

  private final CharacterViewQueryService queryService;

  @Override
  public Object findByUserIgn(String userIgn) {
    return queryService.findByUserIgn(userIgn);
  }

  @Override
  public void deleteByUserIgn(String userIgn) {
    queryService.deleteByUserIgn(userIgn);
  }
}
