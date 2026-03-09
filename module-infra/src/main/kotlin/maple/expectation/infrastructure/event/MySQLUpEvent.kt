package maple.expectation.infrastructure.event

import java.time.Instant

/**
 * MySQL 복구 감지 이벤트
 *
 * <p>CircuitBreaker(likeSyncDb)가 CLOSED 상태로 전이될 때 발행됩니다.
 *
 * <p>DynamicTTLManager가 이 이벤트를 수신하여 TTL 복원 및 Compensation Sync를 시작합니다.
 *
 * @param timestamp 복구 감지 시각
 * @param circuitBreakerName CircuitBreaker 이름
 * @param fromState 이전 상태 (OPEN, HALF_OPEN 등)
 * @param toState 현재 상태 (CLOSED)
 */
data class MySQLUpEvent(
    val timestamp: Instant,
    val circuitBreakerName: String,
    val fromState: String,
    val toState: String,
) {
    companion object {
        fun of(cbName: String, fromState: String, toState: String): MySQLUpEvent = MySQLUpEvent(Instant.now(), cbName, fromState, toState)
    }
}
