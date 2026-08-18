package maple.expectation.infrastructure.config

import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculatorFactory
import maple.expectation.infrastructure.calculation.LegacyValuationConfiguration
import maple.expectation.infrastructure.persistence.repository.CubeProbabilityRepositoryImpl
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Legacy calculator facade auto-configuration.
 *
 * <p>Preserves the app-facing factory/repository/configuration bean surface while
 * delegating calculation and probability ownership to the core valuation contract.
 * <ul>
 *   <li>LegacyValuationConfiguration: immutable snapshot and pure core kernel</li>
 *   <li>EquipmentExpectationCalculatorFactory: stable public V4 facade</li>
 *   <li>CubeProbabilityRepositoryImpl: stable projection repository bean</li>
 * </ul>
 */
@Configuration
@Import(
    LegacyValuationConfiguration::class,
    EquipmentExpectationCalculatorFactory::class,
    CubeProbabilityRepositoryImpl::class,
)
class CalculatorEngineAutoConfiguration
