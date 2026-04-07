package maple.expectation.infrastructure.pgmq

import java.time.Instant

/**
 * PGMQ 메시지 래퍼 (ADR-002)
 *
 * <h3>역할</h3>
 * <p>PGMQ에서 읽은 메시지를 타입 안전하게 래핑
 *
 * <h3>구조</h3>
 * <pre>
 * PGMQ Row:
 * msg_id | read_ct | enqueued_at | vt | message
 * 123    | 0       | 2026-03-09  | ... | {"ocid":"abc"}
 * </pre>
 *
 * @param T 메시지 페이로드 타입
 */
data class PgmqMessage<T>(
    /** 메시지 ID (PGMQ 자동 생성) */
    val messageId: Long,

    /** 읽기 횟수 (재시도 추적용) */
    val readCount: Int,

    /** 큐에 추가된 시점 */
    val enqueuedAt: Instant,

    /** Visibility Timeout 만료 시점 */
    val visibilityTimeout: Instant,

    /** 메시지 페이로드 */
    val payload: T,
) {
    companion object {
        /**
         * PGMQ ResultSet에서 메시지 생성
         *
         * @param messageId 메시지 ID
         * @param readCount 읽기 횟수
         * @param enqueuedAt 큐 추가 시점
         * @param vt Visibility Timeout
         * @param payload 파싱된 페이로드
         * @return PgmqMessage 인스턴스
         */
        fun <T> of(
            messageId: Long,
            readCount: Int,
            enqueuedAt: Instant,
            vt: Instant,
            payload: T,
        ): PgmqMessage<T> = PgmqMessage(
            messageId = messageId,
            readCount = readCount,
            enqueuedAt = enqueuedAt,
            visibilityTimeout = vt,
            payload = payload,
        )
    }

    /**
     * 메시지가 재시도 가능한지 확인
     *
     * @param maxRetries 최대 재시도 횟수
     * @return 재시도 가능하면 true
     */
    fun isRetryable(maxRetries: Int = 3): Boolean = readCount < maxRetries

    /**
     * 처리 대기 시간 계산
     *
     * @return 큐에서 대기한 시간
     */
    fun waitingDuration(): java.time.Duration = java.time.Duration.between(enqueuedAt, Instant.now())
}

/**
 * 계산 요청 메시지 페이로드
 *
 * @param ocid 캐릭터 OCID
 * @param userIgn 사용자 IGN
 * @param presetNo 프리셋 번호
 * @param forceRecalculation 강제 재계산 여부
 * @param requestedAt 요청 시점
 */
data class CalculationRequest(
    val ocid: String,
    val userIgn: String,
    val presetNo: Int = 1,
    val forceRecalculation: Boolean = false,
    val requestedAt: String,
)

/**
 * 기부 알림 메시지 페이로드
 *
 * @param donationId 기부 ID
 * @param userId 사용자 ID
 * @param amount 금액
 * @param message 메시지
 * @param requestedAt 요청 시점
 */
data class DonationRequest(
    val donationId: Long,
    val userId: Long,
    val amount: Long,
    val message: String?,
    val requestedAt: String,
)

/**
 * Nexon 데이터 수집 요청 메시지 페이로드 (ADR-006)
 *
 * <p>calculation_queue로 발행되어 CalculationWorker가 처리
 *
 * @param ocid 캐릭터 OCID
 * @param userIgn 사용자 IGN
 * @param requestedAt 요청 시점
 */
data class NexonCollectionRequest(
    val ocid: String,
    val userIgn: String,
    val requestedAt: String,
)

/**
 * 기대값 계산 요청 메시지 페이로드 (Issue #634)
 *
 * <p>expectation_calc_high / expectation_calc_low 큐로 발행
 *
 * @param userIgn 캐릭터 IGN
 * @param forceRecalculation 강제 재계산 여부
 */
data class ExpectationCalcMessage(
    val userIgn: String,
    val forceRecalculation: Boolean,
)

/**
 * Nexon API 재시도 메시지 페이로드 (Phase 3 - PGMQ Migration)
 *
 * <p>nexon_retry_queue로 발행되어 NexonApiPgmqProcessor가 처리
 *
 * @param eventType API 이벤트 타입
 * @param payload 요청 파라미터 (characterName 또는 OCID)
 * @param retryCount 현재 재시도 횟수
 * @param contentHash 무결성 검증용 SHA-256 해시
 * @param requestId 멱등성 요청 ID
 */
data class NexonRetryMessage(
    val eventType: String,
    val payload: String,
    val retryCount: Int = 0,
    val contentHash: String,
    val requestId: String,
)

/**
 * FanOut 배치 처리 재시도 메시지 페이로드
 *
 * <p>nexon_fanout_queue로 발행되어 NexonFanOutWorker가 처리.
 * 429 Rate Limit 발생 시 Batch Lane에서 enqueue.
 *
 * @param ocid 캐릭터 OCID
 * @param userIgn 사용자 IGN
 * @param retryCount 현재 재시도 횟수
 * @param requestedAt 요청 시점
 */
data class FanOutRequest(
    val ocid: String,
    val userIgn: String,
    val retryCount: Int = 0,
    val requestedAt: String,
)
