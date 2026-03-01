package maple.expectation.adapter.in;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.inbound.AlertPort;
import maple.expectation.infrastructure.notification.discord.DiscordAlertService;
import org.springframework.stereotype.Component;

/**
 * AlertPort 구현체 (ADR-005)
 *
 * <p>책임: DiscordAlertService에 위임(delegate)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertPortAdapter implements AlertPort {

  private final DiscordAlertService discordAlertService;

  @Override
  public void sendCriticalAlert(String title, String description, Throwable error) {
    discordAlertService.sendCriticalAlert(title, description, error);
  }
}
