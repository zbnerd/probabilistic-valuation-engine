package maple.restcontroller.read

import maple.restcontroller.metrics.V6ReadMetrics
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult
import maple.expectation.util.StringMaskingUtils.maskIgn

class ExpectationReadFacade(
    private val registry: InflightRequestRegistry,
    private val buffer: RequestBuffer,
    private val metrics: V6ReadMetrics
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun enqueue(userIgn: String, deferred: DeferredResult<ResponseEntity<*>>) {
        metrics.requestTotal.increment()

        val isFirst = registry.register(userIgn, deferred)

        if (isFirst) {
            metrics.dedupMissTotal.increment()
            val request = ReadRequest(userIgn = userIgn)

            if (!buffer.offer(request)) {
                metrics.bufferRejectedTotal.increment()
                registry.cleanup(userIgn, deferred)
                log.warn("Buffer full, rejecting request userIgn={}", maskIgn(userIgn))
                deferred.setErrorResult(
                    ResponseEntity.status(503)
                        .header("Retry-After", "1")
                        .build<Any>()
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
                ResponseEntity.accepted().build<Any>()
            )
        }

        deferred.onCompletion {
            registry.cleanup(userIgn, deferred)
        }
    }
}
