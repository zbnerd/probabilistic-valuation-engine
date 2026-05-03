package maple.expectation.infrastructure.mq.pgmq

data class PgmqTopicConfig(
    val batchSize: Int = 10,
    val visibilityTimeoutSec: Int = 120,
    val maxRetries: Int = 3,
)
