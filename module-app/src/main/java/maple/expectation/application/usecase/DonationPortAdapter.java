package maple.expectation.application.usecase;

import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.inbound.DonationCommand;
import maple.expectation.core.port.inbound.DonationPort;
import maple.expectation.infrastructure.queue.pgmq.DonationQueueProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DonationPort 구현체 (ADR-005)
 *
 * <p>책임: 도네이션(커피 후원) 기능 구현
 *
 * <p>V5 Migration: DonationOutbox 제거 후 PGMQ 직접 발행.
 * 같은 트랜잭션 내에서 비즈니스 로직과 메시지 발행을 처리합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DonationPortAdapter implements DonationPort {

  private final DonationQueueProducer donationQueueProducer;

  @Value("${spring.auth.admin.allowlist:}")
  private String adminAllowlist;

  @Override
  public void sendCoffee(DonationCommand command) {
    log.info(
        "[Donation] Coffee donation request: guest={}, admin={}, amount={}, requestId={}",
        command.getGuestUuid(),
        maskFingerprint(command.getAdminFingerprint()),
        command.getAmount(),
        command.getRequestId());

    // Validate admin fingerprint
    if (!isAdmin(command.getAdminFingerprint())) {
      throw new IllegalArgumentException(
          "Invalid admin fingerprint: " + maskFingerprint(command.getAdminFingerprint()));
    }

    // Publish notification to PGMQ directly (Outbox bridge removed)
    long donationId = System.currentTimeMillis();
    donationQueueProducer.publish(
        donationId,
        0L,
        command.getAmount(),
        command.getGuestUuid());

    log.info("[Donation] Coffee sent successfully: requestId={}, donationId={}", command.getRequestId(), donationId);
  }

  @Override
  public boolean isAdmin(String fingerprint) {
    if (adminAllowlist == null || adminAllowlist.isBlank()) {
      return false;
    }

    Set<String> admins = parseAllowlist(adminAllowlist);
    return admins.contains(fingerprint);
  }

  private Set<String> parseAllowlist(String allowlist) {
    Set<String> admins = new HashSet<>();
    if (allowlist != null && !allowlist.isBlank()) {
      for (String fp : allowlist.split(",")) {
        String trimmed = fp.trim();
        if (!trimmed.isEmpty()) {
          admins.add(trimmed);
        }
      }
    }
    return admins;
  }

  private String maskFingerprint(String fingerprint) {
    if (fingerprint == null || fingerprint.length() < 10) {
      return "***";
    }
    return fingerprint.substring(0, 6) + "..." + fingerprint.substring(fingerprint.length() - 4);
  }
}
