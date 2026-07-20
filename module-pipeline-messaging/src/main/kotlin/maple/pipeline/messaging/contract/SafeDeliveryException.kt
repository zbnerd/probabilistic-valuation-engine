package maple.pipeline.messaging.contract

class SafeDeliveryException(
    reason: String,
    attempt: Int,
) : RuntimeException(
    "pipeline delivery ${requireNormalizedReason(reason)} attempt=${requirePositiveAttempt(attempt)}",
    null,
    false,
    false,
)

private fun requirePositiveAttempt(attempt: Int): Int = attempt.also {
    require(it > 0)
}
