package maple.expectation.core.domain.model

import java.math.BigDecimal

/**
 * 계산 결과 기반 클래스
 * 모든 계산 도메인의 결과는 이 클래스를 상속받는다
 */
sealed class CalculationResult(
    open val correlationId: String,
    open val timestamp: Long,
    open val metadata: Map<String, Any> = emptyMap(),
)

/**
 * 강화 결과
 */
data class EnhanceResult(
    override val correlationId: String,
    override val timestamp: Long,
    override val metadata: Map<String, Any> = emptyMap(),
    val enhanceLevel: Int,
    val targetLevel: Int,
    val expectedCost: BigDecimal,
    val successRate: BigDecimal,
    val failureCost: BigDecimal,
) : CalculationResult(correlationId, timestamp, metadata)

/**
 * 큐브 결과
 */
data class CubeResult(
    override val correlationId: String,
    override val timestamp: Long,
    override val metadata: Map<String, Any> = emptyMap(),
    val cubeType: String,
    val itemLevel: Int,
    val itemPart: String,
    val itemGrade: String,
    val slotCount: Int,
    val expectedCost: BigDecimal,
    val successRate: BigDecimal,
    val options: List<String>,
) : CalculationResult(correlationId, timestamp, metadata)

/**
 * 플레임 결과
 */
data class FlameResult(
    override val correlationId: String,
    override val timestamp: Long,
    override val metadata: Map<String, Any> = emptyMap(),
    val equipCategory: String,
    val targetScore: Int,
    val expectedTrials: BigDecimal,
    val expectedCost: BigDecimal,
    val successRate: BigDecimal,
) : CalculationResult(correlationId, timestamp, metadata)

/**
 * 스타포스 결과
 */
data class StarforceResult(
    override val correlationId: String,
    override val timestamp: Long,
    override val metadata: Map<String, Any> = emptyMap(),
    val currentStar: Int,
    val targetStar: Int,
    val itemLevel: Int,
    val options: String,
    val expectedCost: BigDecimal,
    val successRate: BigDecimal,
    val enhancementCost: BigDecimal,
    val baseCost: BigDecimal,
) : CalculationResult(correlationId, timestamp, metadata)
