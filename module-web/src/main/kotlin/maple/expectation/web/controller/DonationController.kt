package maple.expectation.web.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import maple.expectation.web.dto.donation.SendCoffeeRequest
import maple.expectation.web.dto.donation.SendCoffeeResponse
import maple.expectation.core.port.inbound.DonationCommand
import maple.expectation.core.port.inbound.DonationPort
import maple.expectation.infrastructure.security.AuthenticatedUser
import maple.expectation.response.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/**
 * 도네이션(커피 후원) API 컨트롤러
 *
 * 게스트가 Admin(개발자)에게 커피를 사주는 기능입니다.
 *
 * API 목록:
 * - POST /api/v2/donation/coffee - Admin에게 커피 보내기
 *
 * **Issue #151: Bean Validation 적용**
 * - @Validated: 클래스 레벨 검증 활성화
 * - @Valid: @RequestBody DTO 검증
 */
@Validated
@RestController
@RequestMapping("/api/v2/donation")
@Tag(name = "Donation", description = "도네이션(커피 후원) API")
class DonationController(
    private val donationPort: DonationPort,
    private val asyncExecutor: ExecutorService
) {

    /**
     * Admin(개발자)에게 커피 보내기
     *
     * 인증된 사용자만 사용할 수 있으며, ADMIN_FINGERPRINTS에 등록된 Admin에게만 후원할 수 있습니다.
     *
     * **PR #189 Fix: Idempotency-Key 헤더 지원**
     * 클라이언트가 `Idempotency-Key` 헤더를 제공하면 해당 값을 requestId로 사용하여 동일 요청의 중복 처리를 방지합니다.
     *
     * @param user 인증된 사용자 정보 (발신자)
     * @param request 후원 요청 (수신자 fingerprint, 금액)
     * @param idempotencyKey 멱등성 보장을 위한 클라이언트 제공 키 (선택)
     * @return 후원 결과
     */
    @PostMapping("/coffee")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    @Operation(summary = "커피 후원", description = "Admin(개발자)에게 커피를 후원합니다.")
    fun sendCoffee(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: SendCoffeeRequest,
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?
    ): CompletableFuture<ResponseEntity<ApiResponse<SendCoffeeResponse>>> {
        // PR #189: 멱등성 키 우선 사용 (없으면 서버에서 생성)
        val requestId = if (!idempotencyKey.isNullOrBlank()) {
            idempotencyKey
        } else {
            UUID.randomUUID().toString()
        }

        // 발신자는 인증된 사용자의 fingerprint를 UUID로 사용
        val guestUuid = user.fingerprint

        // ADR-005: DonationPort 사용 (Hexagonal Architecture)
        val command = DonationCommand.of(
            guestUuid,
            request.adminFingerprint,
            request.amount,
            requestId
        )

        // ADR-039 Fix: Use dedicated executor instead of ForkJoinPool.commonPool()
        return CompletableFuture.runAsync({ donationPort.sendCoffee(command) }, asyncExecutor)
            .thenApply {
                log.info("[Donation] Coffee sent successfully: requestId={}", requestId)
                ResponseEntity.ok(ApiResponse.success(SendCoffeeResponse.success(requestId)))
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DonationController::class.java)
    }
}
