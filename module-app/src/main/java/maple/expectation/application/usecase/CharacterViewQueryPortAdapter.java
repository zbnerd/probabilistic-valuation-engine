package maple.expectation.application.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.inbound.CharacterViewQueryPort;
import maple.expectation.infrastructure.persistence.CharacterViewQueryServicePostgres;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * CharacterViewQueryPort 구현체 (ADR-005)
 *
 * <p>책임: CharacterViewQueryServicePostgres에 위임 (PostgreSQL)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.v5.enabled", havingValue = "true", matchIfMissing = false)
public class CharacterViewQueryPortAdapter implements CharacterViewQueryPort {

  private final CharacterViewQueryServicePostgres queryService;

  @Override
  public Object findByUserIgn(String userIgn) {
    return queryService.findByUserIgn(userIgn);
  }

  @Override
  public void deleteByUserIgn(String userIgn) {
    queryService.deleteByUserIgn(userIgn);
  }
}
