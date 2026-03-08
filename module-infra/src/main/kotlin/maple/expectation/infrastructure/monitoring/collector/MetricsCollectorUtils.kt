package maple.expectation.infrastructure.monitoring.collector

import kotlin.math.round

/**
 * Shared utilities for metrics collectors.
 *
 * <p>Provides common formatting and helper methods to avoid code duplication
 * across different metrics collector implementations.
 */
object MetricsCollectorUtils {

  /**
   * Formats a Double value to 2 decimal places, handling NaN and Infinity.
   *
   * @param value The value to format
   * @return The formatted value, or 0.0 if NaN or Infinite
   */
  fun formatDouble(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) {
      return 0.0
    }
    return round(value * 100.0) / 100.0
  }

  /**
   * Calculates a percentage ratio with safety checks.
   *
   * @param numerator The numerator value
   * @param denominator The denominator value (must be > 0)
   * @return The percentage (0-100), or 0.0 if denominator is invalid
   */
  fun calculatePercentage(numerator: Double, denominator: Double): Double {
    if (denominator <= 0) {
      return 0.0
    }
    return formatDouble((numerator / denominator) * 100)
  }

  /**
   * Calculates a ratio with safety checks.
   *
   * @param numerator The numerator value
   * @param denominator The denominator value (must be > 0)
   * @return The ratio (0.0-1.0), or 0.0 if denominator is invalid
   */
  fun calculateRatio(numerator: Double, denominator: Double): Double {
    if (denominator <= 0) {
      return 0.0
    }
    return formatDouble(numerator / denominator)
  }
}
