package maple.expectation.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import maple.expectation.core.port.inbound.DlqPort;
import maple.expectation.response.ApiResponse;
import maple.expectation.web.dto.common.CursorPageResponse;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DLQ 관리 API (Admin 전용) - ADR-005 이관
 *
 * <h3>엔드포인트</h3>
 *
 * <ul>
 *   <li>GET /api/admin/dlq - DLQ 목록 조회 (페이징)
 *   <li>GET /api/admin/dlq/{id} - DLQ 상세 조회
 *   <li>POST /api/admin/dlq/{id}/reprocess - DLQ 재처리
 *   <li>DELETE /api/admin/dlq/{id} - DLQ 폐기
 *   <li>GET /api/admin/dlq/count - DLQ 총 건수
 *   <li>GET /api/admin/dlq/v2 - DLQ 목록 조회 (Cursor 방식)
 * </ul>
 */
@Tag(name = "DLQ Admin", description = "Dead Letter Queue 관리 API (Admin 전용)")
@RestController
@RequestMapping("/api/admin/dlq")
@RequiredArgsConstructor
public class DlqAdminController {

  private final DlqPort dlqPort;

  /** DLQ 목록 조회 (페이징) */
  @Operation(summary = "DLQ 목록 조회", description = "최신순으로 DLQ 항목 목록을 조회합니다.")
  @GetMapping
  public CompletableFuture<ResponseEntity<ApiResponse<Page<?>>>> findAll(
      @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page,
      @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size) {

    return CompletableFuture.supplyAsync(
        () -> {
          @SuppressWarnings("unchecked")
          Page<?> result = (Page<?>) dlqPort.findAll(page, size);
          return ResponseEntity.ok(ApiResponse.success(result));
        });
  }

  /** DLQ 상세 조회 */
  @Operation(summary = "DLQ 상세 조회", description = "특정 DLQ 항목의 상세 정보를 조회합니다 (전체 payload 포함).")
  @GetMapping("/{id}")
  public CompletableFuture<ResponseEntity<ApiResponse<?>>> findById(
      @Parameter(description = "DLQ ID") @PathVariable Long id) {

    return CompletableFuture.supplyAsync(
        () -> {
          Object result = dlqPort.findById(id);
          return ResponseEntity.ok(ApiResponse.success(result));
        });
  }

  /** DLQ 재처리 (Outbox로 복원) */
  @Operation(summary = "DLQ 재처리", description = "DLQ 항목을 Outbox로 복원하여 재처리합니다.")
  @PostMapping("/{id}/reprocess")
  public CompletableFuture<ResponseEntity<ApiResponse<?>>> reprocess(
      @Parameter(description = "DLQ ID") @PathVariable Long id) {

    return CompletableFuture.supplyAsync(
        () -> {
          Object result = dlqPort.reprocess(id);
          return ResponseEntity.ok(ApiResponse.success(result));
        });
  }

  /** DLQ 폐기 (삭제) */
  @Operation(summary = "DLQ 폐기", description = "복구 불가능한 DLQ 항목을 삭제합니다.")
  @DeleteMapping("/{id}")
  public CompletableFuture<ResponseEntity<ApiResponse<String>>> discard(
      @Parameter(description = "DLQ ID") @PathVariable Long id) {

    return CompletableFuture.supplyAsync(
        () -> {
          dlqPort.discard(id);
          return ResponseEntity.ok(ApiResponse.success("DLQ entry discarded successfully: " + id));
        });
  }

  /** DLQ 총 건수 조회 */
  @Operation(summary = "DLQ 총 건수", description = "현재 DLQ에 쌓인 총 항목 수를 조회합니다.")
  @GetMapping("/count")
  public CompletableFuture<ResponseEntity<ApiResponse<Long>>> count() {
    return CompletableFuture.supplyAsync(
        () -> {
          long count = dlqPort.count();
          return ResponseEntity.ok(ApiResponse.success(count));
        });
  }

  // ========== Cursor-based Pagination (#233) ==========

  /**
   * DLQ 목록 조회 (Cursor-based Pagination)
   *
   * <p>기존 OFFSET 기반 페이징의 O(n) 성능 문제를 Keyset Pagination으로 해결.
   */
  @Operation(
      summary = "DLQ 목록 조회 (Cursor 방식)",
      description = "Cursor-based Pagination으로 DLQ 목록을 조회합니다. Deep Paging에서도 O(1) 성능을 보장합니다.")
  @GetMapping("/v2")
  public CompletableFuture<ResponseEntity<ApiResponse<CursorPageResponse<?>>>> findAllByCursor(
      @Parameter(description = "이전 페이지의 마지막 ID (첫 페이지는 생략)") @RequestParam(required = false)
          Long cursor,
      @Parameter(description = "페이지 크기 (최대 100)") @RequestParam(defaultValue = "20") int size) {
    return CompletableFuture.supplyAsync(
        () -> {
          @SuppressWarnings("unchecked")
          CursorPageResponse<?> result =
              (CursorPageResponse<?>) dlqPort.findAllByCursor(cursor, size);
          return ResponseEntity.ok(ApiResponse.success(result));
        });
  }
}
