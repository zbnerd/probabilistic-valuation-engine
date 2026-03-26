package maple.expectation.infrastructure.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Global Admission Control Configuration for Issue #617
 *
 * Enables GlobalAdmissionProperties for @ConfigurationProperties binding.
 */
@Configuration
@EnableConfigurationProperties(GlobalAdmissionProperties::class)
class GlobalAdmissionConfig
