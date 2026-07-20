package maple.pipeline.messaging.contract

import java.time.Duration

private val normalizedReason = Regex("[A-Z0-9_]{1,64}")

sealed interface DeliveryOutcome {
    data object Success : DeliveryOutcome

    data class TerminalDrop(val reason: String) : DeliveryOutcome {
        init {
            require(normalizedReason.matches(reason))
        }
    }

    data class InvalidMessage(val reason: String) : DeliveryOutcome {
        init {
            require(normalizedReason.matches(reason))
        }
    }

    data class Retryable(val cause: Throwable) : DeliveryOutcome

    data class Backpressure(val duration: Duration) : DeliveryOutcome {
        init {
            require(!duration.isNegative && !duration.isZero) {
                "backpressure duration must be positive"
            }
        }
    }
}

internal fun requireNormalizedReason(reason: String): String = reason.also {
    require(normalizedReason.matches(it))
}
