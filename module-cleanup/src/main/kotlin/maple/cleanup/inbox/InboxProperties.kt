package maple.cleanup.inbox

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cleanup-inbox")
data class InboxProperties(
    val topic: String = "synchronizer.chunk.consumed",
    val consumerGroup: String = "cleanup-inbox",
    val basePath: String = "../data",
    val maxPending: Int = 10_000,
    val autoStart: Boolean = true,
)
