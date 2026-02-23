package maple.expectation.infrastructure.jdbc.config;

import java.time.Duration;
import lombok.Getter;

/**
 * Configuration for JDBC batch operation retry logic.
 *
 * <p>Provides configurable retry behavior for transient database failures.
 *
 * @see maple.expectation.infrastructure.jdbc.JdbcBatchUpsertRepository
 */
@Getter
public class JdbcBatchRetryConfig {

  /** Default retry configuration: 3 retries with exponential backoff. */
  public static final JdbcBatchRetryConfig DEFAULT =
      new JdbcBatchRetryConfig(3, Duration.ofMillis(100), 2.0);

  private final int maxRetries;
  private final Duration initialBackoff;
  private final double backoffMultiplier;

  /**
   * Creates a new retry configuration.
   *
   * @param maxRetries maximum number of retry attempts (must be >= 0)
   * @param initialBackoff initial backoff duration between retries
   * @param backoffMultiplier multiplier for exponential backoff (must be >= 1.0)
   * @throws IllegalArgumentException if parameters are invalid
   */
  public JdbcBatchRetryConfig(int maxRetries, Duration initialBackoff, double backoffMultiplier) {
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be >= 0: " + maxRetries);
    }
    if (initialBackoff == null || initialBackoff.isNegative()) {
      throw new IllegalArgumentException("initialBackoff must be positive: " + initialBackoff);
    }
    if (backoffMultiplier < 1.0) {
      throw new IllegalArgumentException("backoffMultiplier must be >= 1.0: " + backoffMultiplier);
    }

    this.maxRetries = maxRetries;
    this.initialBackoff = initialBackoff;
    this.backoffMultiplier = backoffMultiplier;
  }

  /** Creates a retry configuration with no retries. */
  public static JdbcBatchRetryConfig noRetry() {
    return new JdbcBatchRetryConfig(0, Duration.ZERO, 1.0);
  }

  /**
   * Calculates the backoff duration for the given retry attempt.
   *
   * @param attempt the retry attempt number (0-based)
   * @return the backoff duration
   */
  public Duration getBackoffForAttempt(int attempt) {
    long millis = (long) (initialBackoff.toMillis() * Math.pow(backoffMultiplier, attempt));
    return Duration.ofMillis(millis);
  }

  /** Creates a new configuration with a different max retry count. */
  public JdbcBatchRetryConfig withMaxRetries(int maxRetries) {
    return new JdbcBatchRetryConfig(maxRetries, this.initialBackoff, this.backoffMultiplier);
  }

  /** Creates a new configuration with a different initial backoff. */
  public JdbcBatchRetryConfig withInitialBackoff(Duration initialBackoff) {
    return new JdbcBatchRetryConfig(this.maxRetries, initialBackoff, this.backoffMultiplier);
  }
}
