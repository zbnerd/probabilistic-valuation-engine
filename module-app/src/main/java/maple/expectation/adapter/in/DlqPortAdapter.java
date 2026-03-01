package maple.expectation.adapter.in;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.inbound.DlqPort;
import maple.expectation.service.v2.donation.outbox.DlqAdminService;
import maple.expectation.web.dto.page.CursorPageRequest;
import org.springframework.stereotype.Component;

/**
 * DlqPort 구현체 (ADR-005)
 *
 * <p>책임: DlqAdminService에 위임(delegate)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqPortAdapter implements DlqPort {

  private final DlqAdminService dlqAdminService;

  @Override
  public Object findAll(int page, int size) {
    return dlqAdminService.findAll(page, size);
  }

  @Override
  public Object findById(long id) {
    return dlqAdminService.findById(id);
  }

  @Override
  public Object reprocess(long id) {
    return dlqAdminService.reprocess(id);
  }

  @Override
  public void discard(long id) {
    dlqAdminService.discard(id);
  }

  @Override
  public long count() {
    return dlqAdminService.count();
  }

  @Override
  public Object findAllByCursor(Long cursor, int size) {
    CursorPageRequest request = CursorPageRequest.Companion.of(cursor, size);
    return dlqAdminService.findAllByCursor(request);
  }
}
