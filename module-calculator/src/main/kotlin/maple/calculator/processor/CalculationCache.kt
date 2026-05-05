package maple.calculator.processor

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculatorFactory
import maple.expectation.core.dto.cube.CubeCalculationInput
import org.springframework.stereotype.Component

@Component
class CalculationCache(
    private val factory: EquipmentExpectationCalculatorFactory,
) {
    private val potentialCache: Cache<String, Double> = Caffeine.newBuilder()
        .maximumSize(50_000)
        .build()

    private val additionalCache: Cache<String, Double> = Caffeine.newBuilder()
        .maximumSize(50_000)
        .build()

    private val starforceCache: Cache<String, Double> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .build()

    fun calculatePotential(input: CubeCalculationInput): Double {
        val key = "${input.itemName}:potential:${input.grade}:${input.options?.joinToString(",")}"
        return potentialCache.get(key) {
            factory.createBlackCubeCalculator(input).calculateCost()
        }
    }

    fun calculateAdditional(input: CubeCalculationInput): Double {
        val key = "${input.itemName}:additional:${input.additionalGrade}:${input.additionalOptions?.joinToString(",")}"
        return additionalCache.get(key) {
            factory.createAdditionalCubeCalculator(input).calculateCost()
        }
    }

    fun calculateStarforce(itemName: String, itemLevel: Int, currentStar: Int, targetStar: Int): Double {
        val key = "$itemName:starforce:$targetStar"
        return starforceCache.get(key) {
            factory.createStarforceCalculator(itemName, itemLevel, currentStar, targetStar).calculateCost()
        }
    }

    fun stats(): String {
        return "potential=${potentialCache.estimatedSize()} additional=${additionalCache.estimatedSize()} starforce=${starforceCache.estimatedSize()}"
    }
}
