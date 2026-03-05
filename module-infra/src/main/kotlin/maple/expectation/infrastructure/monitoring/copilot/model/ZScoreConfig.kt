package maple.expectation.infrastructure.monitoring.copilot.model

/**
 * Z-Score Configuration for Statistical Anomaly Detection
 *
 * Configures statistical anomaly detection using Z-score (standard deviations from mean).
 *
 * ### Z-Score Formula
 * ```
 * z = (value - mean) / stdDev
 * ```
 *
 * ### Interpretation
 * - |z| >= 3.0: Highly anomalous (99.7% confidence)
 * - |z| >= 2.5: Very anomalous (98.8% confidence)
 * - |z| >= 2.0: Moderately anomalous (95.4% confidence)
 */
data class ZScoreConfig(
    /** Enable Z-score detection */
    val enabled: Boolean = false,
    
    /** Number of data points to use for calculating mean/stdDev */
    val windowPoints: Int = 30,
    
    /** Z-score threshold for triggering anomaly */
    val threshold: Double = 3.0,
    
    /** Minimum required points for Z-score calculation */
    val minRequiredPoints: Int = 10
) {
    /** Validate configuration */
    fun validate() {
        if (enabled) {
            require(windowPoints >= minRequiredPoints) {
                "windowPoints ($windowPoints) must be >= minRequiredPoints ($minRequiredPoints)"
            }
            require(threshold > 0) {
                "threshold ($threshold) must be > 0"
            }
        }
    }
}
