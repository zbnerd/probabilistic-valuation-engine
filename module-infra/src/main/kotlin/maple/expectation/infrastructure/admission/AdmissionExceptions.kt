package maple.expectation.infrastructure.admission

/**
 * 🔥 ADMISSION CONTROL EXCEPTIONS
 *
 * Unified exception classes for admission control system.
 */

/**
 * Thrown when request waits too long in admission queue
 */
class AdmissionTimeoutException(message: String) : RuntimeException(message)

/**
 * Thrown when admission queue is full (fast reject)
 */
class AdmissionRejectedException(message: String) : RuntimeException(message)

/**
 * Thrown when request is served with degraded response
 */
class DegradedException(
    message: String,
    val level: DegradationLevel
) : RuntimeException(message) {
    enum class DegradationLevel {
        FRESH_CACHE,
        STALE_CACHE,
        FALLBACK
    }
}
