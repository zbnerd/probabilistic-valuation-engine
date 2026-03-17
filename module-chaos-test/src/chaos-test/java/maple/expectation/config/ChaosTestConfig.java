package maple.expectation.config;

import maple.expectation.common.resource.ResourceLoader;
import maple.expectation.infrastructure.ratelimit.config.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Chaos Test Configuration providing common beans for chaos/nightmare tests.
 *
 * <p>This configuration is automatically loaded for all chaos tests to provide common
 * infrastructure beans.
 */
@TestConfiguration
@EnableConfigurationProperties({
  RateLimitProperties.class,
})
public class ChaosTestConfig {

  /**
   * ResourceLoader bean for infrastructure components.
   *
   * <p>Required by components like TwoBucketRateLimiter that need to load resources.
   */
  @Bean
  public ResourceLoader resourceLoader() {
    return new ResourceLoader();
  }
}
