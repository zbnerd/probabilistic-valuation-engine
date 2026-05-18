package maple.restcontroller.read

import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult

class ExpectationReadFacade(
    private val registry: InflightRequestRegistry,
    private val buffer: RequestBuffer,
    private val metrics: V6ReadMetrics,
    private val cacheService: ReadModelCacheService,
    private val properties: V6ReadProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun enqueue(userIgn: String, presetNo: Int, deferred: DeferredResult<ResponseEntity<*>>) {
        metrics.requestTotal.increment()
        val firstRequest = registry.register(userIgn, presetNo, deferred)
        if (firstRequest) {
            metrics.dedupMissTotal.increment()
            if (!buffer.offer(ReadRequest(userIgn = userIgn, presetNo = presetNo))) {
                metrics.bufferRejectedTotal.increment()
                registry.cleanup(userIgn, presetNo, deferred)
                log.warn("Buffer full, rejecting request userIgn={}", maskIgn(userIgn))
                deferred.setErrorResult(
                    ResponseEntity.status(503)
                        .header("Retry-After", "1")
                        .build<Any>(),
                )
                return
            }
            log.debug("Buffered read request userIgn={}", maskIgn(userIgn))
        } else {
            metrics.dedupHitTotal.increment()
            log.debug("Dedup hit for userIgn={}", maskIgn(userIgn))
        }

        deferred.onTimeout {
            metrics.timeoutTotal.increment()
            deferred.setErrorResult(
                ResponseEntity.accepted()
                    .header("Location", cacheService.statusUrl(userIgn, presetNo))
                    .header("Retry-After", properties.statusRetryAfterSeconds.toString())
                    .body(cacheService.status(userIgn, presetNo)),
            )
        }
        deferred.onCompletion {
            registry.cleanup(userIgn, presetNo, deferred)
        }
    }
}
