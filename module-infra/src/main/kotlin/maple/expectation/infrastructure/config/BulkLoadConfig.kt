package maple.expectation.infrastructure.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Bulk Loading Configuration for Issue #611
 *
 * Enables BulkLoadProperties for @ConfigurationProperties binding.
 */
@Configuration
@EnableConfigurationProperties(BulkLoadProperties::class)
class BulkLoadConfig
