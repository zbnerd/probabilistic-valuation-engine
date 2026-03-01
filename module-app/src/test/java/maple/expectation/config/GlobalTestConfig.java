package maple.expectation.config;

/**
 * Global test configuration providing common beans for all tests.
 *
 * <p>This configuration is automatically loaded for all tests to provide common infrastructure
 * beans that may be missing in test context.
 *
 * <p>Note: ResourceLoader bean is provided by {@code MessagingConfig} in production. Do not
 * duplicate here to avoid BeanDefinitionOverrideException.
 */
@org.springframework.boot.test.context.TestConfiguration
public class GlobalTestConfig {
  // ResourceLoader is provided by MessagingConfig - no need to duplicate here
}
