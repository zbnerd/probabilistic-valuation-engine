package maple.expectation.core.dto.like

import java.io.Serializable
import java.time.Instant

/**
 * 좋아요 이벤트 DTO (Scale-out Pub/Sub)
 *
 * Issue #278: Scale-out 환경 실시간 좋아요 동기화
 *
 * 인스턴스 간 L1 캐시 무효화를 위한 이벤트 메시지
 *
 * @property userIgn 대상 캐릭터 닉네임 (캐시 키)
 * @property newDelta 버퍼의 새 delta 값 (HINCRBY 반환값)
 * @property eventType 이벤트 타입 (LIKE, UNLIKE)
 * @property sourceInstanceId 이벤트 발행 인스턴스 ID (Self-skip용)
 * @property timestamp 이벤트 발생 시각 (디버깅/메트릭용)
 */
data class LikeEvent(
    val userIgn: String,
    val newDelta: Long,
    val eventType: EventType,
    val sourceInstanceId: String,
    val timestamp: Instant
) : Serializable {

    companion object {
        @JvmStatic
        fun like(userIgn: String, newDelta: Long, instanceId: String): LikeEvent =
            LikeEvent(userIgn, newDelta, EventType.LIKE, instanceId, Instant.now())

        @JvmStatic
        fun unlike(userIgn: String, newDelta: Long, instanceId: String): LikeEvent =
            LikeEvent(userIgn, newDelta, EventType.UNLIKE, instanceId, Instant.now())
    }

    enum class EventType {
        LIKE,
        UNLIKE
    }
}
