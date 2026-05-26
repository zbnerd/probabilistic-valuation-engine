package maple.calculator

import maple.calculator.config.PipelineProperties
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculatorFactory
import maple.expectation.application.service.cube.CubeServiceImpl
import maple.expectation.application.service.cube.component.CubeComputeBuffer
import maple.expectation.application.service.cube.component.CubeDpCalculator
import maple.expectation.application.service.cube.component.CubeSlotCountResolver
import maple.expectation.application.service.cube.component.DpModeInferrer
import maple.expectation.application.service.cube.component.SlotDistributionBuilder
import maple.expectation.application.service.cube.component.StatValueExtractor
import maple.expectation.application.service.cube.policy.CubeCostPolicy
import maple.expectation.application.service.starforce.StarforceLookupAdapter
import maple.expectation.config.CubeEngineFeatureFlag
import maple.expectation.config.TableMassConfig
import maple.expectation.infrastructure.adapter.policy.PolicyAdapter
import maple.expectation.infrastructure.config.CalculationPortConfig
import maple.expectation.infrastructure.config.ExecutorConfig
import maple.expectation.infrastructure.executor.DefaultLogicExecutor
import maple.expectation.infrastructure.executor.classifier.DefaultExceptionClassifier
import maple.expectation.infrastructure.persistence.repository.CubeProbabilityRepositoryImpl
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(exclude = [SecurityAutoConfiguration::class, ManagementWebSecurityAutoConfiguration::class])
@EnableScheduling
@EnableConfigurationProperties(PipelineProperties::class)
@Import(
    EquipmentExpectationCalculatorFactory::class,
    CubeServiceImpl::class,
    CubeDpCalculator::class,
    CubeComputeBuffer::class,
    CubeSlotCountResolver::class,
    DpModeInferrer::class,
    SlotDistributionBuilder::class,
    StatValueExtractor::class,
    CubeCostPolicy::class,
    StarforceLookupAdapter::class,
    PolicyAdapter::class,
    DefaultLogicExecutor::class,
    DefaultExceptionClassifier::class,
    CubeProbabilityRepositoryImpl::class,
    CubeEngineFeatureFlag::class,
    TableMassConfig::class,
    CalculationPortConfig::class,
    ExecutorConfig::class,
)
class CalculatorApplication

fun main(args: Array<String>) {
    runApplication<CalculatorApplication>(*args)
}
