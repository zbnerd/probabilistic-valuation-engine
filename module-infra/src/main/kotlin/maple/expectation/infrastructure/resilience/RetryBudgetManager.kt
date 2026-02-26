package maple.expectation.infrastructure.resilience

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

@Component
class RetryBudgetManager(
    private val properties: RetryBudgetProperties,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(RetryBudgetManager::class.java)

    private val retryCounter = LongAdder()
    private val windowStartEpoch = AtomicLong(Instant.now().epochSecond)

    private var attemptsCounter: Counter? = null
    private var allowedCounter: Counter? = null
    private var rejectedCounter: Counter? = null

    fun tryAcquire(serviceName: String): Boolean {
        if (!properties.isEnabled) {
            return true
        }

        resetWindowIfNeeded()

        val currentCount = retryCounter.sum()
        val maxRetries = properties.maxRetriesPerMinute

        recordAttempt()

        if (currentCount >= maxRetries) {
            recordRejection(serviceName)
            logger.warn(
                "[RetryBudget] 예산 소진으로 재시도 차단. serviceName={}, current={}, limit={}, window={}s",
                serviceName,
                currentCount,
                maxRetries,
                getWindowElapsedSeconds()
            )
            return false
        }

        retryCounter.increment()
        recordAllowed(serviceName)

        logger.debug(
            "[RetryBudget] 예산 허용. serviceName={}, count={}/{}",
            serviceName,
            currentCount + 1,
            maxRetries
        )
        return true
    }

    fun getConsumptionRate(): Double {
        val currentCount = retryCounter.sum()
        val maxRetries = properties.maxRetriesPerMinute
        return currentCount.toDouble() / maxRetries
    }

    fun getWindowElapsedSeconds(): Long {
        val currentEpoch = Instant.now().epochSecond
        val startEpoch = windowStartEpoch.get()
        return currentEpoch - startEpoch
    }

    fun getWindowRemainingSeconds(): Long {
        val elapsed = getWindowElapsedSeconds()
        val windowSize = properties.windowSizeSeconds
        return maxOf(0L, windowSize - elapsed)
    }

    fun getCurrentRetryCount(): Long = retryCounter.sum()

    fun reset() {
        retryCounter.reset()
        windowStartEpoch.set(Instant.now().epochSecond)
        logger.info("[RetryBudget] 윈도우 리셋됨")
    }

    private fun resetWindowIfNeeded() {
        val currentEpoch = Instant.now().epochSecond
        val startEpoch = windowStartEpoch.get()
        val windowSize = properties.windowSizeSeconds

        if (currentEpoch - startEpoch >= windowSize) {
            windowStartEpoch.set(currentEpoch)
            retryCounter.reset()
            logger.debug(
                "[RetryBudget] 윈도우 경과로 리셋. elapsed={}s, limit={}s",
                currentEpoch - startEpoch,
                windowSize
            )
        }
    }

    private fun recordAttempt() {
        if (properties.isMetricsEnabled) {
            if (attemptsCounter == null) {
                attemptsCounter = Counter.builder("retry_budget_attempts_total")
                    .description("Total retry budget acquisition attempts")
                    .register(meterRegistry)
            }
            attemptsCounter?.increment()
        }
    }

    private fun recordAllowed(serviceName: String) {
        if (properties.isMetricsEnabled) {
            if (allowedCounter == null) {
                allowedCounter = Counter.builder("retry_budget_allowed_total")
                    .description("Total retry budget allowances")
                    .tag("service", serviceName)
                    .register(meterRegistry)
            }
            allowedCounter?.increment()
        }
    }

    private fun recordRejection(serviceName: String) {
        if (properties.isMetricsEnabled) {
            if (rejectedCounter == null) {
                rejectedCounter = Counter.builder("retry_budget_rejected_total")
                    .description("Total retry budget rejections")
                    .tag("service", serviceName)
                    .register(meterRegistry)
            }
            rejectedCounter?.increment()
        }
    }
}
