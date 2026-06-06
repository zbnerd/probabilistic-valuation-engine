package maple.calculator.processor

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculatorFactory
import maple.expectation.core.dto.v4.EquipmentCalculationInput
import org.springframework.stereotype.Component

/** Caffeine max size — 100k entries × ~256 B/entry ≈ 25 MB heap. Sized to keep 5 min of calc hot-set in memory. */
private const val CACHE_MAX_SIZE: Long = 100_000L

@Component
class CalculationCache(
    private val factory: EquipmentExpectationCalculatorFactory,
) {
    data class CacheKey(
        val itemName: String,
        val itemPart: String,
        val itemLevel: Int,
        val potentialGrade: String?,
        val potentialOptions: List<String?>?,
        val additionalPotentialGrade: String?,
        val additionalPotentialOptions: List<String?>?,
        val targetStar: Int,
        val isNoljang: Boolean,
    )

    data class ComponentCosts(
        val blackCubeCost: Double?,
        val additionalCubeCost: Double?,
        val starforceCost: Double?,
    ) {
        val hasAnyCost: Boolean = blackCubeCost != null || additionalCubeCost != null || starforceCost != null
        val totalCost: Double?
            get() = if (hasAnyCost) {
                (blackCubeCost ?: 0.0) + (additionalCubeCost ?: 0.0) + (starforceCost ?: 0.0)
            } else {
                null
            }

        companion object {
            fun empty(): ComponentCosts = ComponentCosts(null, null, null)
        }
    }

    private val cache: Cache<CacheKey, ComponentCosts> = Caffeine.newBuilder()
        .maximumSize(CACHE_MAX_SIZE)
        .recordStats()
        .build()

    fun calculate(input: EquipmentCalculationInput): ComponentCosts {
        val key = CacheKey(
            itemName = input.itemName,
            itemPart = input.itemPart,
            itemLevel = input.itemLevel,
            potentialGrade = input.potentialGrade,
            potentialOptions = input.potentialOptions,
            additionalPotentialGrade = input.additionalPotentialGrade,
            additionalPotentialOptions = input.additionalPotentialOptions,
            targetStar = input.targetStar,
            isNoljang = input.isNoljang,
        )
        return cache.get(key) {
            val calculator = factory.createFullCalculator(input)
            val details = calculator.detailedCosts
            ComponentCosts(
                blackCubeCost = details.blackCubeCost,
                additionalCubeCost = details.additionalCubeCost,
                starforceCost = details.starforceCost,
            )
        }
    }

    fun stats(): String {
        val s = cache.stats()
        return "size=${cache.estimatedSize()} hits=${s.hitCount()} misses=${s.missCount()} hitRate=${"%.1f%%".format(s.hitRate() * 100)}"
    }
}
