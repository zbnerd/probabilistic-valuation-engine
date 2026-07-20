package maple.calculator.processor

import maple.calculator.cache.OffHeapCacheBackend
import maple.calculator.metrics.ValuationCacheMetrics
import maple.expectation.core.calculation.ValuationInput
import maple.expectation.core.calculation.ValuationKernel
import maple.expectation.core.calculation.ValuationResult
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot

data class ValuationCacheKey(
    val input: ValuationInput,
    val tableLogicalVersion: String,
    val tableContentSha256: String,
    val logicVersion: String,
)

class ValuationCache(
    private val kernel: ValuationKernel,
    private val table: ProbabilityTableSnapshot,
    private val backend: OffHeapCacheBackend<ValuationCacheKey, ValuationResult>,
    private val metrics: ValuationCacheMetrics,
    private val logicVersion: String = ValuationKernel.LOGIC_VERSION,
) {
    fun calculate(input: ValuationInput): ValuationResult {
        val key = ValuationCacheKey(
            input = input,
            tableLogicalVersion = table.version.logical,
            tableContentSha256 = table.version.contentSha256,
            logicVersion = logicVersion,
        )
        read(key)?.let { cached -> return cached }

        val calculated = kernel.calculate(input, table)
        write(key, calculated)
        return calculated
    }

    fun backend(): OffHeapCacheBackend<ValuationCacheKey, ValuationResult> = backend

    private fun read(key: ValuationCacheKey): ValuationResult? {
        val errorsBefore = backend.stats().errors
        val read = runCatching { backend.get(key) }
        val backendReportedFailure = backend.stats().errors > errorsBefore
        if (read.isFailure || backendReportedFailure) {
            metrics.recordGetFailure()
        }
        return read.getOrNull()
    }

    private fun write(key: ValuationCacheKey, value: ValuationResult) {
        val errorsBefore = backend.stats().errors
        val write = runCatching { backend.put(key, value) }
        val backendReportedFailure = backend.stats().errors > errorsBefore
        if (write.isFailure || backendReportedFailure) {
            metrics.recordPutFailure()
        }
    }
}
