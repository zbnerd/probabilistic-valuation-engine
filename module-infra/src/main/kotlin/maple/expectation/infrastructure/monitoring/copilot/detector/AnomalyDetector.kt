package maple.expectation.infrastructure.monitoring.copilot.detector

import java.util.Optional
import maple.expectation.core.util.KahanSummation
import maple.expectation.infrastructure.monitoring.copilot.model.*
import org.slf4j.LoggerFactory

class AnomalyDetector {

    companion object {
        private const val FIRST_TIME_SERIES_INDEX = 0
        private val log = LoggerFactory.getLogger(AnomalyDetector::class.java)
    }

    fun detect(
        signal: SignalDefinition,
        timeSeriesList: List<TimeSeries>?,
        nowMillis: Long,
        zScoreConfig: ZScoreConfig?,
    ): Optional<AnomalyEvent> {
        if (timeSeriesList.isNullOrEmpty()) {
            log.debug("[AnomalyDetector] No time series data for signal: {}", signal.panelTitle)
            return Optional.empty()
        }

        val currentValue = extractLatestValue(timeSeriesList)
        if (currentValue == null) {
            log.debug("[AnomalyDetector] No valid metric value for signal: {}", signal.panelTitle)
            return Optional.empty()
        }

        val thresholdAnomaly = detectThresholdBased(signal, currentValue, nowMillis)
        if (thresholdAnomaly.isPresent) {
            return thresholdAnomaly
        }

        if (zScoreConfig != null && zScoreConfig.enabled) {
            return detectZScoreBased(signal, timeSeriesList, currentValue, nowMillis, zScoreConfig)
        }

        return Optional.empty()
    }

    private fun detectThresholdBased(
        signal: SignalDefinition,
        currentValue: Double,
        detectedAtMillis: Long,
    ): Optional<AnomalyEvent> {
        val severityMapping = signal.severityMapping
        if (severityMapping == null) {
            return Optional.empty()
        }

        val warnThreshold = severityMapping.warnThreshold
        val critThreshold = severityMapping.critThreshold
        val comparator = severityMapping.comparator

        if (critThreshold != null && exceedsThreshold(currentValue, critThreshold, comparator)) {
            return Optional.of(
                AnomalyEvent(
                    signalId = signal.id,
                    severity = "CRIT",
                    reason = buildReason(signal, currentValue, critThreshold, comparator, "CRIT"),
                    detectedAtMillis = detectedAtMillis,
                    currentValue = currentValue,
                    baselineValue = critThreshold,
                ),
            )
        }

        if (warnThreshold != null && exceedsThreshold(currentValue, warnThreshold, comparator)) {
            return Optional.of(
                AnomalyEvent(
                    signalId = signal.id,
                    severity = "WARN",
                    reason = buildReason(signal, currentValue, warnThreshold, comparator, "WARN"),
                    detectedAtMillis = detectedAtMillis,
                    currentValue = currentValue,
                    baselineValue = warnThreshold,
                ),
            )
        }

        return Optional.empty()
    }

    private fun detectZScoreBased(
        signal: SignalDefinition,
        timeSeriesList: List<TimeSeries>,
        currentValue: Double,
        detectedAtMillis: Long,
        config: ZScoreConfig,
    ): Optional<AnomalyEvent> {
        config.validate()

        val values = extractAllValues(timeSeriesList)
        if (values.size < config.minRequiredPoints) {
            log.debug("[AnomalyDetector] Insufficient data for Z-score: {}/{} required", values.size, config.minRequiredPoints)
            return Optional.empty()
        }

        val mean = calculateMean(values)
        val stdDev = calculateStdDev(values, mean)

        if (stdDev == 0.0) {
            log.debug("[AnomalyDetector] Zero stdDev - all values are identical for signal: {}", signal.panelTitle)
            return Optional.empty()
        }

        val zScore = Math.abs((currentValue - mean) / stdDev)

        log.debug("[AnomalyDetector] Z-score calculation for {}: mean={:.2f}, stdDev={:.2f}, z={:.2f}", signal.panelTitle, mean, stdDev, zScore)

        if (zScore >= config.threshold) {
            val severity = determineSeverityFromZScore(zScore)
            return Optional.of(
                AnomalyEvent(
                    signalId = signal.id,
                    severity = severity,
                    reason = buildZScoreReason(signal, currentValue, mean, stdDev, zScore, config),
                    detectedAtMillis = detectedAtMillis,
                    currentValue = currentValue,
                    baselineValue = mean,
                ),
            )
        }

        return Optional.empty()
    }

    private fun extractLatestValue(timeSeriesList: List<TimeSeries>): Double? {
        if (timeSeriesList.isEmpty()) {
            return null
        }

        val latest = timeSeriesList[FIRST_TIME_SERIES_INDEX]
        if (latest.points.isEmpty()) {
            return null
        }

        val latestPoint = latest.points[latest.points.size - 1]
        return latestPoint.value
    }

    private fun extractAllValues(timeSeriesList: List<TimeSeries>): List<Double> {
        val values = mutableListOf<Double>()

        for (series in timeSeriesList) {
            for (point in series.points) {
                if (!point.value.isNaN() && !point.value.isInfinite()) {
                    values.add(point.value)
                }
            }
        }

        return values
    }

    private fun exceedsThreshold(value: Double, threshold: Double, comparator: String?): Boolean {
        val comp = comparator ?: ">"
        return when (comp.trim()) {
            ">", "gt", "greater than" -> value > threshold
            ">=", "gte", "greater than or equal" -> value >= threshold
            "<", "lt", "less than" -> value < threshold
            "<=", "lte", "less than or equal" -> value <= threshold
            "==", "eq", "equal" -> Math.abs(value - threshold) < 0.0001
            else -> {
                log.warn("[AnomalyDetector] Unknown comparator: {}, defaulting to '>'", comp)
                value > threshold
            }
        }
    }

    /**
     * Calculate mean using Kahan Summation for improved numerical accuracy
     *
     * @param values list of double values
     * @return mean value
     */
    private fun calculateMean(values: List<Double>): Double {
        if (values.isEmpty()) {
            return 0.0
        }

        // 🔥 P2 FIX #2: Use Kahan Summation for accurate floating-point accumulation
        val sum = KahanSummation.sum(values)
        return sum / values.size
    }

    /**
     * Calculate standard deviation using Kahan Summation for improved numerical accuracy
     *
     * @param values list of double values
     * @param mean pre-calculated mean value
     * @return standard deviation
     */
    private fun calculateStdDev(values: List<Double>, mean: Double): Double {
        if (values.size < 2) {
            return 0.0
        }

        // 🔥 P2 FIX #2: Use Kahan Summation for accurate floating-point accumulation
        val squaredDiffs = values.map { value ->
            val diff = value - mean
            diff * diff
        }
        val sumSquaredDiff = KahanSummation.sum(squaredDiffs)

        return Math.sqrt(sumSquaredDiff / (values.size - 1))
    }

    private fun determineSeverityFromZScore(zScore: Double): String = when {
        zScore >= 4.0 -> "CRIT"
        zScore >= 3.0 -> "CRIT"
        zScore >= 2.5 -> "WARN"
        else -> "WARN"
    }

    private fun buildReason(
        signal: SignalDefinition,
        currentValue: Double,
        threshold: Double,
        comparator: String?,
        severity: String,
    ): String = "[%s] %s: Current value %.2f %s threshold %.2f (%s)".format(
        severity,
        signal.panelTitle,
        currentValue,
        comparator ?: ">",
        threshold,
        signal.unit ?: "",
    ).trim()

    private fun buildZScoreReason(
        signal: SignalDefinition,
        currentValue: Double,
        mean: Double,
        stdDev: Double,
        zScore: Double,
        config: ZScoreConfig,
    ): String = "[Z-SCORE] %s: Value %.2f deviates %.2fσ from baseline %.2f (σ=%.2f, threshold=%.1f)".format(
        signal.panelTitle,
        currentValue,
        zScore,
        mean,
        stdDev,
        config.threshold,
    )
}
