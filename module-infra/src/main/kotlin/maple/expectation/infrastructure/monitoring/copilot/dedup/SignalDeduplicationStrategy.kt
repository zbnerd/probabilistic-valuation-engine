package maple.expectation.infrastructure.monitoring.copilot.dedup

import maple.expectation.infrastructure.monitoring.copilot.model.AnomalyEvent
import maple.expectation.infrastructure.monitoring.copilot.model.SignalDefinition

/**
 * Strategy interface for signal deduplication.
 *
 * <p>Implementations provide different approaches to prevent duplicate anomaly notifications within
 * a time window.
 *
 * <h3>CLAUDE.md Compliance</h3>
 *
 * <ul>
 *   <li>Section 4: Strategy Pattern for pluggable deduplication algorithms
 *   <li>Section 6: Interface Segregation Principle (ISP)
 *   <li>Stateless: No server-bound state for scale-out
 * </ul>
 */
interface SignalDeduplicationStrategy {

  /**
   * Check if an anomaly event should be skipped due to recent detection.
   *
   * @param event The anomaly event to check
   * @param signal The signal definition (for query/threshold info)
   * @param currentTimestamp Current timestamp in milliseconds
   * @return true if the event should be skipped (duplicate), false if it should be processed
   */
  fun shouldSkip(event: AnomalyEvent, signal: SignalDefinition, currentTimestamp: Long): Boolean

  /**
   * Record an anomaly detection for future deduplication.
   *
   * @param event The anomaly event that was detected
   * @param currentTimestamp Current timestamp in milliseconds
   */
  fun recordDetection(event: AnomalyEvent, currentTimestamp: Long)

  /**
   * Cleanup stale entries from deduplication state.
   *
   * @param currentTimestamp Current timestamp in milliseconds
   */
  fun cleanup(currentTimestamp: Long)
}
