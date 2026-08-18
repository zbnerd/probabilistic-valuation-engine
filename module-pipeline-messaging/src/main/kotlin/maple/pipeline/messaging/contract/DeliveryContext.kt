package maple.pipeline.messaging.contract

import java.time.Instant

data class DeliveryContext(
    val listenerId: String,
    val topic: String,
    val partition: Int,
    val offset: Long,
    val timestamp: Instant,
    val key: String?,
    val deliveryAttempt: Int,
)
