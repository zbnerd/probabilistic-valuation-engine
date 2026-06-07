package maple.externalapi.scheduler.phase

import java.time.Instant

/**
 * Immutable batch state shared by phase batch loops (OCID lookup, character-basic,
 * item-equipment). Accumulators are updated via [copy] producing a new instance.
 *
 * Use [shouldLogProgress] / [markLogged] to drive periodic progress logging without
 * leaking the [lastProgressLog] field through the loop body.
 */
data class BatchProgress(
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastProgressLog: Int = 0,
    val start: Instant = Instant.now(),
) {
    fun totalProcessed(): Int = successCount + failCount

    fun shouldLogProgress(logInterval: Int): Boolean =
        totalProcessed() - lastProgressLog >= logInterval

    fun markLogged(): BatchProgress = copy(lastProgressLog = totalProcessed())

    fun addSuccess(count: Int): BatchProgress = copy(successCount = successCount + count)

    fun addFailure(count: Int): BatchProgress = copy(failCount = failCount + count)
}
