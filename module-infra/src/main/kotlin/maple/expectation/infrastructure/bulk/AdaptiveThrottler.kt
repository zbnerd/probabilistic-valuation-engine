package maple.expectation.infrastructure.bulk

import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 동적 속도 조절 쓰로틀러 (Issue #611)
 *
 * API 응답(429, 성공, 타임아웃)에 따라 배치 크기와 지연 시간을 동적으로 조절합니다.
 */
@Component
class AdaptiveThrottler(
    @Value("\${bulk.batch.initial-size:100}")
    currentBatchSize: Int,

    @Value("\${bulk.batch.min-size:10}")
    private val minBatchSize: Int,

    @Value("\${bulk.batch.max-size:200}")
    private val maxBatchSize: Int,

    @Value("\${bulk.delay.initial-ms:100}")
    currentDelayMs: Long,

    @Value("\${bulk.delay.min-ms:50}")
    private val minDelayMs: Long,

    @Value("\${bulk.delay.max-ms:5000}")
    private val maxDelayMs: Long,
) {
    private val log = LoggerFactory.getLogger(AdaptiveThrottler::class.java)

    @Volatile
    private var currentBatchSize: Int = currentBatchSize.coerceIn(minBatchSize, maxBatchSize)

    @Volatile
    private var currentDelayMs: Long = currentDelayMs.coerceIn(minDelayMs, maxDelayMs)

    private val consecutive429s = AtomicInteger(0)
    private val consecutiveSuccesses = AtomicInteger(0)

    companion object {
        private const val SUCCESS_STREAK_THRESHOLD = 5
        private const val BATCH_SIZE_INCREMENT = 10
        private const val BATCH_SIZE_DECREMENT = 20
        private const val DELAY_MULTIPLIER = 2.0
        private const val DELAY_DIVISOR = 1.5
    }

    /**
     * 쓰로틀 결정을 나타내는 sealed class
     */
    sealed class ThrottleDecision(
        val batchSize: Int,
        val delayMs: Long,
        val shouldPause: Boolean,
    ) {
        /**
         * 정상 진행 결정
         */
        class Proceed(batchSize: Int, delayMs: Long) : ThrottleDecision(batchSize, delayMs, false)

        /**
         * 일시 정지 결정 (429 과다 시)
         */
        class Pause(batchSize: Int, delayMs: Long) : ThrottleDecision(batchSize, delayMs, true)
    }

    /**
     * API 호출 성공 시 호출
     *
     * 연속 성공 streak가 5 이상이면 배치 크기를 증가하고 지연을 감소합니다.
     */
    fun onSuccess(): ThrottleDecision {
        val streak = consecutiveSuccesses.incrementAndGet()
        consecutive429s.set(0)

        return if (streak >= SUCCESS_STREAK_THRESHOLD) {
            consecutiveSuccesses.set(0)
            increaseBatchSize()
            decreaseDelay()
            log.debug("[Throttler] Success streak reset. Increased batch to {}, delay to {}", currentBatchSize, currentDelayMs)
            ThrottleDecision.Proceed(currentBatchSize, currentDelayMs)
        } else {
            ThrottleDecision.Proceed(currentBatchSize, currentDelayMs)
        }
    }

    /**
     * 429 Too Many Requests 응답 시 호출
     *
     * 배치 크기를 감소시키고 지연을 증가시킵니다 (지수 백오프).
     * 429가 연속 3회 이상이면 일시 정지를 권장합니다.
     */
    fun on429(): ThrottleDecision {
        consecutiveSuccesses.set(0)
        val count = consecutive429s.incrementAndGet()

        decreaseBatchSize()
        increaseDelay()

        val shouldPause = count >= 3
        log.debug(
            "[Throttler] 429 received (count: {}). Decreased batch to {}, delay to {}, pause: {}",
            count,
            currentBatchSize,
            currentDelayMs,
            shouldPause,
        )

        return if (shouldPause) {
            ThrottleDecision.Pause(currentBatchSize, currentDelayMs)
        } else {
            ThrottleDecision.Proceed(currentBatchSize, currentDelayMs)
        }
    }

    /**
     * 타임아웃 발생 시 호출
     *
     * 중간 정도의 백오프를 적용합니다.
     */
    fun onTimeout(): ThrottleDecision {
        consecutiveSuccesses.set(0)
        consecutive429s.set(0)

        // Moderate backoff: 배치 크기 10% 감소, 지연 50% 증가
        currentBatchSize = (currentBatchSize * 0.9).toInt().coerceAtLeast(minBatchSize)
        currentDelayMs = (currentDelayMs * 1.5).toLong().coerceAtMost(maxDelayMs)

        log.debug("[Throttler] Timeout. Decreased batch to {}, delay to {}", currentBatchSize, currentDelayMs)
        return ThrottleDecision.Proceed(currentBatchSize, currentDelayMs)
    }

    /**
     * 현재 배치 크기를 반환합니다.
     */
    fun getCurrentBatchSize(): Int = currentBatchSize

    /**
     * 현재 지연 시간(ms)을 반환합니다.
     */
    fun getCurrentDelayMs(): Long = currentDelayMs

    private fun increaseBatchSize() {
        currentBatchSize = (currentBatchSize + BATCH_SIZE_INCREMENT).coerceAtMost(maxBatchSize)
    }

    private fun decreaseBatchSize() {
        currentBatchSize = (currentBatchSize - BATCH_SIZE_DECREMENT).coerceAtLeast(minBatchSize)
    }

    private fun increaseDelay() {
        currentDelayMs = (currentDelayMs * DELAY_MULTIPLIER).toLong().coerceIn(minDelayMs, maxDelayMs)
    }

    private fun decreaseDelay() {
        currentDelayMs = (currentDelayMs / DELAY_DIVISOR).toLong().coerceAtLeast(minDelayMs)
    }
}
