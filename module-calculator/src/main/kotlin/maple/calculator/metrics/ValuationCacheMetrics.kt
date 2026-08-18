package maple.calculator.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry

class ValuationCacheMetrics(
    registry: MeterRegistry,
) {
    private val getFailures = failureCounter(registry, GET_OPERATION)
    private val putFailures = failureCounter(registry, PUT_OPERATION)

    fun recordGetFailure() = getFailures.increment()

    fun recordPutFailure() = putFailures.increment()

    private fun failureCounter(registry: MeterRegistry, operation: String): Counter = Counter.builder(FAILURE_COUNTER)
        .description("Valuation cache failures isolated from direct calculation")
        .tag(OPERATION_TAG, operation)
        .register(registry)

    companion object {
        const val FAILURE_COUNTER = "calculator.valuation.cache.failures"
        private const val OPERATION_TAG = "operation"
        private const val GET_OPERATION = "get"
        private const val PUT_OPERATION = "put"
    }
}
