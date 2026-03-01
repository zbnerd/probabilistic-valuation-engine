package maple.expectation.adapter.in;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.inbound.AdminPort;
import maple.expectation.service.v2.auth.AdminService;
import org.springframework.stereotype.Component;

/**
 * AdminPort 구현체 (ADR-005)
 *
 * <p>책임: AdminService에 위임(delegate)
 *
 * <p>위임 이유:
 *
 * <ul>
 *   <li>순환 의존성 해결: module-web → module-app → module-core
 *   <li>기존 Service 로직 재사용
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminPortAdapter implements AdminPort {

  private final AdminService adminService;

  @Override
  public boolean isAdmin(String fingerprint) {
    return adminService.isAdmin(fingerprint);
  }

  @Override
  public void addAdmin(String fingerprint) {
    adminService.addAdmin(fingerprint);
  }

  @Override
  public boolean removeAdmin(String fingerprint) {
    return adminService.removeAdmin(fingerprint);
  }

  @Override
  public Set<String> getAllAdmins() {
    return adminService.getAllAdmins();
  }
}
