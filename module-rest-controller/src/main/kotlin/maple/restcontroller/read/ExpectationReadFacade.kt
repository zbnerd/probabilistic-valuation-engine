package maple.restcontroller.read

import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.popular.PopularCharacterService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult

class ExpectationReadFacade(
    private val registry: InflightRequestRegistry,
    private val buffer: RequestBuffer,
    private val metrics: V6ReadMetrics,
    private val readModelCacheService: ReadModelCacheService,
    private val negativeCacheService: NegativeCacheService,
    private val urgentDedupService: UrgentDedupService,
    private val popularCharacterService: PopularCharacterService,
    private val properties: V6ReadProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Register a deferred and either offer to the buffer (first request) or
     * join an in-flight peer (dedup hit). Returns a typed [EnqueueResult] that
     * the controller maps to a [ResponseEntity] via [EnqueueResponseMapper].
     *
     * If the buffer is full, the deferred is cleaned up and
     * [EnqueueResult.ServiceUnavailable] is returned; the caller is expected
     * to fail the deferred (e.g. with a 503 response).
     *
     * For successful enqueues, this method also wires the deferred's timeout
     * (→ 202 Accepted with status body) and completion (→ registry cleanup)
     * callbacks. The 202 body is built by [EnqueueResponseMapper]; this method
     * does not construct `ResponseEntity` instances directly.
     */
    fun enqueue(userIgn: String, presetNo: Int, deferred: DeferredResult<ResponseEntity<*>>): EnqueueResult {
        popularCharacterService.recordV6ExpectationRequest(userIgn)
        metrics.requestTotal.increment()
        val firstRequest = registry.register(userIgn, presetNo, deferred)
        if (firstRequest) {
            metrics.dedupMissTotal.increment()
            if (!buffer.offer(ReadRequest(userIgn = userIgn, presetNo = presetNo))) {
                metrics.bufferRejectedTotal.increment()
                registry.cleanup(userIgn, presetNo, deferred)
                log.warn("Buffer full, rejecting request userIgn={}", maskIgn(userIgn))
                return EnqueueResult.ServiceUnavailable(reason = "buffer-full")
            }
            log.debug("Buffered read request userIgn={}", maskIgn(userIgn))
        } else {
            metrics.dedupHitTotal.increment()
            log.debug("Dedup hit for userIgn={}", maskIgn(userIgn))
        }

        deferred.onTimeout {
            metrics.timeoutTotal.increment()
            deferred.setErrorResult(
                EnqueueResponseMapper.toTimeoutResponse(
                    userIgn = userIgn,
                    presetNo = presetNo,
                    status = urgentDedupService.status(
                        userIgn = userIgn,
                        presetNo = presetNo,
                        hasReadyCache = readModelCacheService.hasReadyCache(userIgn, presetNo),
                        hasNegativeCache = negativeCacheService.getNegativeCache(userIgn),
                    ),
                    retryAfterSeconds = properties.statusRetryAfterSeconds,
                ),
            )
        }
        deferred.onCompletion {
            registry.cleanup(userIgn, presetNo, deferred)
        }

        return if (firstRequest) EnqueueResult.Queued else EnqueueResult.AlreadyInFlight
    }
}
