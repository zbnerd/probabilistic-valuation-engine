package maple.expectation.infrastructure.config

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
import maple.expectation.infrastructure.executor.DefaultLogicExecutor
import maple.expectation.infrastructure.executor.classifier.DefaultExceptionClassifier
import maple.expectation.infrastructure.persistence.repository.CubeProbabilityRepositoryImpl
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Calculator Engine Auto-Configuration — module-calculator 전용 17-class import facade.
 *
 * <p>module-calculator는 이 클래스 1개만 import하면 큐브 계산 엔진에 필요한
 * 모든 빈을 조립할 수 있다. 큐브 컴포넌트 추가/제거/이름 변경 시
 * module-calculator가 아닌 이 파일만 수정하면 된다.
 *
 * <p>포함 빈 (17):
 * <ul>
 *   <li>Application services: EquipmentExpectationCalculatorFactory, CubeServiceImpl,
 *       CubeComputeBuffer, CubeDpCalculator, CubeSlotCountResolver, DpModeInferrer,
 *       SlotDistributionBuilder, StatValueExtractor, CubeCostPolicy, StarforceLookupAdapter</li>
 *   <li>Config: CubeEngineFeatureFlag, TableMassConfig, CalculationPortConfig, CoreExecutorConfig</li>
 *   <li>Infra: PolicyAdapter, DefaultLogicExecutor, DefaultExceptionClassifier,
 *       CubeProbabilityRepositoryImpl</li>
 * </ul>
 *
 * @see maple.calculator.config.CalculatorEngineConfiguration — module-calculator의 2-import facade
 */
@Configuration
@Import(
    EquipmentExpectationCalculatorFactory::class,
    CubeServiceImpl::class,
    CubeComputeBuffer::class,
    CubeDpCalculator::class,
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
    CoreExecutorConfig::class,
)
class CalculatorEngineAutoConfiguration
