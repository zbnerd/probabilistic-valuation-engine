package maple.expectation.infrastructure.batch

import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * 마이크로 배칭 요청 모델
 *
 * <h3>구조</h3>
 *
 * <ul>
 *   <li>key: 조회 키 (예: IGN)</li>
 *   <li>future: 결과를 비동기로 전달받을 CompletableFuture</li>
 *   <li>requestedAt: 요청 시각 (타임아웃 계산용)</li>
 * </ul>
 *
 * @param T 조회 결과 타입
 * @see maple.expectation.infrastructure.batch.AdaptiveMicroBatchUserService
 */
data class BatchRequest<T>(
    /** 조회 키 (예: IGN, OCID 등) */
    val key: String,

    /** 결과를 비동기로 전달받을 CompletableFuture */
    val future: CompletableFuture<T?>,

    /** 요청 시각 (타임아웃 계산 및 모니터링용) */
    val requestedAt: Instant = Instant.now(),
) {
    companion object {
        /**
         * 팩토리 메서드
         *
         * @param key 조회 키
         * @return 새로운 BatchRequest 인스턴스
         */
        fun <T> of(key: String): BatchRequest<T> = BatchRequest(
            key = key,
            future = CompletableFuture(),
            requestedAt = Instant.now(),
        )
    }
}
