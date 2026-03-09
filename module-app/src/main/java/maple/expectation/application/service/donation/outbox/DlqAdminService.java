package maple.expectation.application.service.donation.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.DonationDlq;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.error.exception.DlqNotFoundException;
import maple.expectation.infrastructure.donation.outbox.OutboxMetrics;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.DonationDlqRepository;
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository;
import maple.expectation.web.dto.dlq.DlqDetailResponse;
import maple.expectation.web.dto.dlq.DlqEntryResponse;
import maple.expectation.web.dto.dlq.DlqReprocessResult;
import maple.expectation.web.dto.page.CursorPageRequest;
import maple.expectation.web.dto.page.CursorPageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DLQ 관리 서비스 (Admin 전용)
 *
 * <h3>기능</h3>
 *
 * <ul>
 *   <li>DLQ 목록/상세 조회
 *   <li>DLQ 재처리 (Outbox로 복원)
 *   <li>DLQ 폐기 (삭제)
 * </ul>
 *
 * <h3>CLAUDE.md 준수</h3>
 *
 * <ul>
 *   <li>LogicExecutor: Zero try-catch 정책
 *   <li>@Transactional: 재처리 시 원자성 보장
 * </ul>
 *
 * @see DonationDlqRepository
 * @see DonationOutboxRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqAdminService {

  private final DonationDlqRepository dlqRepository;
  private final DonationOutboxRepository outboxRepository;
  private final OutboxMetrics metrics;
  private final LogicExecutor executor;

  private static final int DEFAULT_PAGE_SIZE = 20;

  /**
   * DLQ 목록 조회 (페이징)
   *
   * @param page 페이지 번호 (0-based)
   * @param size 페이지 크기
   * @return DLQ 항목 페이지
   */
  @Transactional(transactionManager = "transactionManager", readOnly = true)
  public Page<DlqEntryResponse> findAll(int page, int size) {
    TaskContext context = TaskContext.of("DlqAdmin", "FindAll", String.valueOf(page));

    return executor.execute(
        () -> {
          PageRequest pageRequest = PageRequest.of(page, size > 0 ? size : DEFAULT_PAGE_SIZE);
          Page<DonationDlq> dlqPage = dlqRepository.findAllByOrderByMovedAtDesc(pageRequest);
          return dlqPage.map(DlqEntryResponse::from);
        },
        context);
  }

  /**
   * DLQ 상세 조회
   *
   * @param id DLQ ID
   * @return DLQ 상세 정보
   * @throws DlqNotFoundException DLQ 항목이 없을 경우
   */
  @Transactional(transactionManager = "transactionManager", readOnly = true)
  public DlqDetailResponse findById(Long id) {
    TaskContext context = TaskContext.of("DlqAdmin", "FindById", String.valueOf(id));

    return executor.execute(
        () -> {
          DonationDlq dlq =
              dlqRepository.findById(id).orElseThrow(() -> new DlqNotFoundException(id));
          return DlqDetailResponse.from(dlq);
        },
        context);
  }

  /**
   * DLQ 재처리 (Outbox로 복원)
   *
   * <p>동일 트랜잭션에서 Outbox 생성 후 DLQ 삭제 (원자성 보장)
   *
   * @param id DLQ ID
   * @return 재처리 결과
   * @throws DlqNotFoundException DLQ 항목이 없을 경우
   */
  @Transactional("transactionManager")
  public DlqReprocessResult reprocess(Long id) {
    TaskContext context = TaskContext.of("DlqAdmin", "Reprocess", String.valueOf(id));

    return executor.execute(
        () -> {
          // 1. DLQ 조회
          DonationDlq dlq =
              dlqRepository.findById(id).orElseThrow(() -> new DlqNotFoundException(id));

          log.info("🔄 [DLQ Admin] 재처리 시작: id={}, requestId={}", id, dlq.getRequestId());

          // 2. 기존 Outbox에서 동일 requestId 존재 여부 확인 (멱등성)
          if (outboxRepository.existsByRequestId(dlq.getRequestId())) {
            log.warn("⚠️ [DLQ Admin] 중복 requestId 발견, Outbox 재생성 스킵: {}", dlq.getRequestId());
          } else {
            // 3. 새 Outbox 생성 (PENDING 상태)
            DonationOutbox newOutbox =
                DonationOutbox.create(dlq.getRequestId(), dlq.getEventType(), dlq.getPayload());
            outboxRepository.save(newOutbox);

            log.info("✅ [DLQ Admin] Outbox 복원 완료: newOutboxId={}", newOutbox.getId());
          }

          // 4. DLQ에서 삭제
          dlqRepository.delete(dlq);
          metrics.incrementDlqReprocessed();

          log.info("🗑️ [DLQ Admin] DLQ 삭제 완료: id={}", id);

          return DlqReprocessResult.success(
              id,
              outboxRepository
                  .findByRequestId(dlq.getRequestId())
                  .map(DonationOutbox::getId)
                  .orElse(null),
              dlq.getRequestId());
        },
        context);
  }

  /**
   * DLQ 폐기 (삭제)
   *
   * <p>복구 불가능한 데이터를 수동으로 폐기할 때 사용
   *
   * @param id DLQ ID
   * @throws DlqNotFoundException DLQ 항목이 없을 경우
   */
  @Transactional("transactionManager")
  public void discard(Long id) {
    TaskContext context = TaskContext.of("DlqAdmin", "Discard", String.valueOf(id));

    executor.executeVoidJava(
        () -> {
          DonationDlq dlq =
              dlqRepository.findById(id).orElseThrow(() -> new DlqNotFoundException(id));

          log.warn(
              "🗑️ [DLQ Admin] 폐기 처리: id={}, requestId={}, reason={}",
              id,
              dlq.getRequestId(),
              dlq.getFailureReason());

          dlqRepository.delete(dlq);
          metrics.incrementDlqDiscarded();
        },
        context);
  }

  /**
   * DLQ 총 건수 조회
   *
   * @return DLQ 총 건수
   */
  @Transactional(transactionManager = "transactionManager", readOnly = true)
  public long count() {
    return dlqRepository.countAll();
  }

  // ========== Cursor-based Pagination (#233) ==========

  /**
   * DLQ 목록 조회 (Cursor-based Pagination)
   *
   * <h3>Deep Paging 문제 해결</h3>
   *
   * <p>OFFSET 기반의 O(n) 성능 문제를 Keyset Pagination으로 해결.
   *
   * <ul>
   *   <li>OFFSET 1,000,000: ~1,800ms (1,000,010행 스캔)
   *   <li>Cursor (WHERE id > last): ~10ms (10행만 스캔)
   * </ul>
   *
   * @param request Cursor 페이지 요청
   * @return Cursor 기반 페이지 응답
   */
  @Transactional(transactionManager = "transactionManager", readOnly = true)
  public CursorPageResponse<DlqEntryResponse> findAllByCursor(CursorPageRequest request) {
    TaskContext context =
        TaskContext.of(
            "DlqAdmin",
            "FindByCursor",
            request.getCursor() != null ? String.valueOf(request.getCursor()) : "first");

    return executor.execute(
        () -> {
          PageRequest pageRequest = PageRequest.of(0, request.getSize());

          Slice<DonationDlq> slice =
              (request.getCursor() == null)
                  ? dlqRepository.findFirstPage(pageRequest)
                  : dlqRepository.findByCursorGreaterThan(request.getCursor(), pageRequest);

          return CursorPageResponse.fromWithMapping(
              slice, DlqEntryResponse::from, DonationDlq::getId);
        },
        context);
  }
}
