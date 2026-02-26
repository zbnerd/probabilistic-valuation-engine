package maple.expectation.infrastructure.monitoring.copilot.model

/**
 * Z-Score Configuration for Statistical Anomaly Detection
 *
 * <p>Configures statistical anomaly detection using Z-score (standard deviations from mean).
 *
 * <h3>Z-Score Formula</h3>
 *
 * <pre>
 * z = (value - mean) / stdDev
 * </pre>
 *
 * <h3>Interpretation</h3>
 *
 * <ul>
 *   <li>|z| >= 3.0: Highly anomalous (99.7% confidence)
 *   <li>|z| >= 2.5: Very anomalous (98.8% confidence)
 *   <li>|z| >= 2.0: Moderately anomalous (95.4% confidence)
 * </ul>
 */
data class ZScoreConfig(
    /** Enable Z-score detection */
    val enabled: Boolean = false,

    /**
     * Number of data points to use for calculating mean/stdDev - Recommended: 20-100 points for
     * stable statistics - Too small: High false positive rate - Too large: Slow detection of
     * anomalies
     */
    val windowPoints: Int = 30,

    /**
     * Z-score threshold for triggering anomaly - 3.0 = 99.7% confidence (3-sigma rule) - 2.5 = 98.8%
     * confidence - 2.0 = 95.4% confidence
     */
    val threshold: Double = 3.0,

    /**
     * Minimum required points for Z-score calculation - Prevents unreliable statistics with
     * insufficient data
     */
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
