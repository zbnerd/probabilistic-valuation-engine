package maple.restcontroller.read

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant

sealed class UrgentReadState(@get:JsonValue val name: String) {
    abstract fun retryAfterSeconds(configDefault: Long): Long
    abstract fun shouldTryDb(): Boolean
    open val queuePositionApprox: Long? = null
    open val estimatedWaitSeconds: Long? = null

    companion object {
        const val READY_NAME: String = "READY"
        const val NOT_FOUND_NAME: String = "NOT_FOUND"
        const val PENDING_NAME: String = "PENDING"
        const val UNKNOWN_NAME: String = "UNKNOWN"

        @JsonCreator
        @JvmStatic
        fun fromName(s: String): UrgentReadState = when (s) {
            READY_NAME -> Ready
            NOT_FOUND_NAME -> NotFound
            PENDING_NAME -> Pending(null, null)
            UNKNOWN_NAME -> Unknown
            else -> throw IllegalArgumentException("Unknown UrgentReadState name: $s")
        }
    }

    /** Singleton — use `is Ready` checks, not `===`. */
    object Ready : UrgentReadState(READY_NAME) {
        override fun retryAfterSeconds(configDefault: Long): Long = 0L
        override fun shouldTryDb(): Boolean = false
    }

    /** Singleton — use `is NotFound` checks, not `===`. */
    object NotFound : UrgentReadState(NOT_FOUND_NAME) {
        override fun retryAfterSeconds(configDefault: Long): Long = 0L
        override fun shouldTryDb(): Boolean = false
    }

    data class Pending(
        override val queuePositionApprox: Long?,
        override val estimatedWaitSeconds: Long?,
    ) : UrgentReadState(PENDING_NAME) {
        override fun retryAfterSeconds(configDefault: Long): Long = configDefault
        override fun shouldTryDb(): Boolean = true
    }

    /** Singleton — use `is Unknown` checks, not `===`. */
    object Unknown : UrgentReadState(UNKNOWN_NAME) {
        override fun retryAfterSeconds(configDefault: Long): Long = configDefault
        override fun shouldTryDb(): Boolean = true
    }
}

data class UrgentReadStatusResponse(
    val state: UrgentReadState,
    val userIgn: String,
    val statusUrl: String,
    val queuePositionApprox: Long?,
    val estimatedWaitSeconds: Long?,
    val retryAfterSeconds: Long,
)
