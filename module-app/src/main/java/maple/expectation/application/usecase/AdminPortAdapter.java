package maple.expectation.application.usecase;

import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.inbound.AdminPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AdminPort 구현체 (ADR-005)
 *
 * <p>책임: 설정 파일에서 읽은 bootstrap admin 목록 관리
 *
 * <p>V5 Migration (Issue #589): Redis 기반 AdminService 제거 후 설정 기반 단순 구현으로 대체.
 */
@Slf4j
@Component
public class AdminPortAdapter implements AdminPort {

  /** Bootstrap admin fingerprints (설정에서 읽음, 런타임에 수정 불가) */
  private final Set<String> bootstrapAdmins;

  public AdminPortAdapter(@Value("${spring.auth.admin.allowlist:}") String allowlist) {
    this.bootstrapAdmins = parseAllowlist(allowlist);
    log.info("[AdminPort] Initialized with {} bootstrap admins", bootstrapAdmins.size());
  }

  @Override
  public boolean isAdmin(String fingerprint) {
    return bootstrapAdmins.contains(fingerprint);
  }

  @Override
  public void addAdmin(String fingerprint) {
    // Bootstrap admin만 지원 - 런타임 추가는 지원하지 않음
    log.warn("[AdminPort] Runtime admin addition not supported. Use configuration.");
  }

  @Override
  public boolean removeAdmin(String fingerprint) {
    // Bootstrap admin은 제거 불가
    log.warn("[AdminPort] Bootstrap admin removal not supported.");
    return false;
  }

  @Override
  public Set<String> getAllAdmins() {
    return new HashSet<>(bootstrapAdmins);
  }

  /** 쉼표로 구분된 fingerprint 목록을 Set으로 변환 */
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
}
