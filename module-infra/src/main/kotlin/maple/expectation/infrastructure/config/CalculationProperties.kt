package maple.expectation.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.stereotype.Component

/**
 * Calculation engine configuration properties.
 *
 * <p>Externalizes calculation logic versioning to support OCP (Open/Closed Principle). Version
 * bumps can be done via configuration without code modification.
 *
 * @see maple.expectation.service.v2.EquipmentService
 */
@Component
@ConfigurationProperties(prefix = "calculation")
data class CalculationProperties(
    /** Calculation logic version (used in cache keys) */
    @DefaultValue("3") val logicVersion: Int = 3,

    /** Probability table version (update when cube_tables change) */
    @DefaultValue("2024.01.15") val tableVersion: String = "2024.01.15",
)
