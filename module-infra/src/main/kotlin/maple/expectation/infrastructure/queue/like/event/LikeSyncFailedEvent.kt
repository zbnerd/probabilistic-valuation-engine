package maple.expectation.infrastructure.queue.like.event

import java.time.Instant
import maple.expectation.core.dto.like.FetchResult

/**
 * LikeSync 복구 실패 이벤트 (DLQ 패턴)
 *
 * 금융수준 안전 설계:
 * - 보상 트랜잭션 실패 시 데이터 영구 손실 방지
 * - Event 발행 → Listener에서 파일 백업
 * - 수동 복구 가능한 형태로 데이터 보존
 *
 * @since 2.0.0
 */
data class LikeSyncFailedEvent(
    val tempKey: String?,
    val sourceKey: String,
    val data: Map<String, Long>,
    val failedAt: Instant,
    val errorMessage: String,
) {
    companion object {
        fun fromFetchResult(
            result: FetchResult,
            sourceKey: String,
            cause: Throwable?,
        ): LikeSyncFailedEvent = LikeSyncFailedEvent(
            result.tempKey,
            sourceKey,
            result.data,
            Instant.now(),
            cause?.message ?: "Unknown error",
        )

        fun forSingleEntry(
            userIgn: String,
            count: Long,
            sourceKey: String,
            cause: Throwable?,
        ): LikeSyncFailedEvent = LikeSyncFailedEvent(
            null,
            sourceKey,
            mapOf(userIgn to count),
            Instant.now(),
            cause?.message ?: "Unknown error",
        )
    }

    fun totalCount(): Long = data.values.sum()
    fun size(): Int = data.size
    fun userIgn(): String? = data.keys.firstOrNull()
    fun lostCount(): Long = totalCount()
    fun exception(): RuntimeException = RuntimeException(errorMessage)
}
