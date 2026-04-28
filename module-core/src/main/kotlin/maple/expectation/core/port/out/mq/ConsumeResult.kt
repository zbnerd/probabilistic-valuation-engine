package maple.expectation.core.port.out.mq

import java.time.Duration

sealed class ConsumeResult {
    data object Ack : ConsumeResult()
    data class Retry(val delay: Duration) : ConsumeResult()
    data object Fail : ConsumeResult()
}
