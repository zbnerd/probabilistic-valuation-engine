package maple.expectation.application.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.auth.AdminService;
import maple.expectation.application.service.donation.DonationService;
import maple.expectation.core.port.inbound.DonationCommand;
import maple.expectation.core.port.inbound.DonationPort;
import org.springframework.stereotype.Component;

/**
 * DonationPort 구현체 (ADR-005)
 *
 * <p>책임: DonationService, AdminService에 위임(delegate)
 *
 * <p>위임 이유:
 *
 * <ul>
 *   <li>순환 의존성 해결: module-web → module-core ← module-app
 *   <li>기존 Service 로직 재사용
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DonationPortAdapter implements DonationPort {

  private final DonationService donationService;
  private final AdminService adminService;

  @Override
  public void sendCoffee(DonationCommand command) {
    donationService.sendCoffee(
        command.getGuestUuid(),
        command.getAdminFingerprint(),
        command.getAmount(),
        command.getRequestId());
  }

  @Override
  public boolean isAdmin(String fingerprint) {
    return adminService.isAdmin(fingerprint);
  }
}
