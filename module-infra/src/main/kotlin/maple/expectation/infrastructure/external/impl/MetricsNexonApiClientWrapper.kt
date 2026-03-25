package maple.expectation.infrastructure.external.impl

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.CharacterBasicResponse
import maple.expectation.infrastructure.external.dto.v2.CharacterOcidResponse
import maple.expectation.infrastructure.external.dto.v2.CubeHistoryResponse
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

/**
 * 🔥 Metrics Wrapper for NexonApiClient
 *
 * <p>Purpose: Instrument all Nexon API calls to measure:
 * <ul>
 *   <li>Latency (p50, p95, p99)</li>
 *   <li>Error rate</li>
 *   <li>Request rate</li>
 *   <li>Throttling detection</li>
 * </ul>
 *
 * <p>Usage: Inject this instead of RealNexonApiClient to add observability
 *
 * <p>Architecture: This sits between ResilientNexonApiClient and RealNexonApiClient
 * <pre>
 * ResilientNexonApiClient (circuit breaker, retry, bulkhead)
 *   → MetricsNexonApiClientWrapper (observability + global concurrency control)
 *     → RealNexonApiClient (actual API calls)
 * </pre>
 *
 * <p>🔥 Global Semaphore:
 * <ul>
 *   <li>Limits total concurrent Nexon API calls across ALL endpoints</li>
 *   <li>Prevents downstream saturation (Nexon API latency saturation)</li>
 *   <li>Default value: 10 (for sweep testing: change via code)</li>
 *   <li>Formula: Sustainable RPS = Semaphore / Latency</li>
 * </ul>
 *
 * @param delegate Actual NexonApiClient implementation (RealNexonApiClient)
 * @param meterRegistry Micrometer registry
 */
class MetricsNexonApiClientWrapper(
    private val delegate: NexonApiClient,
    private val meterRegistry: MeterRegistry
) : NexonApiClient {

    private val logger = LoggerFactory.getLogger(MetricsNexonApiClientWrapper::class.java)

    // 🔥 Global semaphore to limit total concurrent Nexon API calls
    // All endpoints share this to prevent Nexon API saturation
    // Optimal value: 50 (sweet spot from sweep testing with 30k users)
    // RPS: 118, Error: 1.0%, p99: 1.23s, Blocked: 1
    private val nexonSemaphore = java.util.concurrent.Semaphore(50)

    // Counters
    private val successCounter: Counter
    private val errorCounter: Counter
    private val timeoutCounter: Counter
    private val throttledCounter: Counter
    private val semaphoreBlockedCounter: Counter

    init {
        // Request counters
        successCounter = Counter.builder("nexon.api.requests")
            .description("Total successful Nexon API requests")
            .tag("status", "success")
            .register(meterRegistry)

        errorCounter = Counter.builder("nexon.api.errors")
            .description("Total Nexon API errors")
            .register(meterRegistry)

        timeoutCounter = Counter.builder("nexon.api.timeouts")
            .description("Nexon API timeouts")
            .register(meterRegistry)

        throttledCounter = Counter.builder("nexon.api.throttled")
            .description("Nexon API throttled (429 rate limit)")
            .register(meterRegistry)

        semaphoreBlockedCounter = Counter.builder("nexon.api.semaphore.blocked")
            .description("Requests blocked waiting for semaphore permit")
            .register(meterRegistry)

        logger.info("[NexonMetrics] Metrics wrapper initialized with global semaphore (permits={})", nexonSemaphore.availablePermits())
    }

    override fun getOcidByCharacterName(characterName: String): CompletableFuture<CharacterOcidResponse> {
        return recordApiCall("getOcid", characterName) {
            delegate.getOcidByCharacterName(characterName)
        }
    }

    override fun getCharacterBasic(ocid: String): CompletableFuture<CharacterBasicResponse> {
        return recordApiCall("getCharacterBasic", ocid) {
            delegate.getCharacterBasic(ocid)
        }
    }

    override fun getItemDataByOcid(ocid: String): CompletableFuture<EquipmentResponse> {
        return recordApiCall("getItemData", ocid) {
            delegate.getItemDataByOcid(ocid)
        }
    }

    override fun getCubeHistory(ocid: String): CompletableFuture<CubeHistoryResponse> {
        return recordApiCall("getCubeHistory", ocid) {
            delegate.getCubeHistory(ocid)
        }
    }

    /**
     * 🔥 Instrument API call with metrics and global semaphore control
     *
     * <p>Flow:
     * <ol>
     *   <li>Acquire semaphore permit (blocks if all permits in use)</li>
     *   <li>Execute API call</li>
     *   <li>Record metrics (latency, errors)</li>
     *   <li>Release semaphore permit</li>
     * </ol>
     *
     * <p>Captures:
     * <ul>
     *   <li>Latency histogram (p50, p95, p99)</li>
     *   <li>Success/error counters</li>
     *   <li>Semaphore blocking time</li>
     *   <li>Exception type tracking</li>
     * </ul>
     */
    private fun <T> recordApiCall(endpoint: String, key: String, block: () -> CompletableFuture<T>): CompletableFuture<T> {
        val sample = Timer.start(meterRegistry)

        // 🔥 Acquire semaphore permit (blocks if no permits available)
        val acquireStart = System.nanoTime()
        nexonSemaphore.acquire()
        val acquireTime = System.nanoTime() - acquireStart

        // Track if we had to wait for permit
        if (acquireTime > 1_000_000) {  // > 1ms
            semaphoreBlockedCounter.increment()
            logger.debug("[NexonSemaphore] {} blocked for {}ms waiting for permit", endpoint, acquireTime / 1_000_000)
        }

        return block()
            .whenComplete { result, ex ->
                try {
                    // 🔥 Always release permit after call completes
                    nexonSemaphore.release()

                    val timer = getTimer(endpoint, if (ex == null) "success" else "error")
                    val duration = sample.stop(timer)  // Returns duration in nanos

                    if (ex == null) {
                        successCounter.increment()
                        logger.debug("[NexonMetrics] {} success: {}ms (acquire: {}ms)",
                            endpoint,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(duration),
                            acquireTime / 1_000_000)
                    } else {
                        recordError(endpoint, ex)
                        logger.warn("[NexonMetrics] {} failed: {} (acquire: {}ms)",
                            endpoint,
                            ex.javaClass.simpleName,
                            acquireTime / 1_000_000)
                    }
                } catch (e: Exception) {
                    logger.error("[NexonMetrics] Metric recording failed", e)
                }
            }
    }

    private fun recordError(endpoint: String, ex: Throwable) {
        errorCounter.increment()

        // Classify error type
        val errorType = when {
            ex is java.util.concurrent.TimeoutException -> "timeout"
            ex?.javaClass?.simpleName?.contains("Timeout") == true -> "timeout"
            ex?.message?.contains("429") == true || ex?.message?.contains("rate limit") == true -> "throttled"
            else -> ex.javaClass.simpleName ?: "unknown"
        }

        when (errorType) {
            "timeout" -> timeoutCounter.increment()
            "throttled" -> throttledCounter.increment()
        }

        // Detailed error counter
        meterRegistry.counter("nexon.api.errors", "endpoint", endpoint, "type", errorType)
            .increment()
    }

    private fun getTimer(endpoint: String, status: String): Timer {
        return Timer.builder("nexon.api.latency")
            .description("Nexon API call latency")
            .tag("endpoint", endpoint)
            .tag("status", status)
            .register(meterRegistry)
    }
}
