package maple.calculator.processor

import maple.calculator.cache.OffHeapCacheBackend
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculatorFactory
import maple.expectation.core.dto.v4.EquipmentCalculationInput
import net.openhft.chronicle.bytes.BytesIn
import net.openhft.chronicle.bytes.BytesMarshallable
import net.openhft.chronicle.bytes.BytesOut
import org.springframework.stereotype.Component

@Component
class CalculationCache(
    private val factory: EquipmentExpectationCalculatorFactory,
    private val backend: OffHeapCacheBackend<CacheKey, ComponentCosts>,
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
    ) : BytesMarshallable {
        override fun writeMarshallable(out: BytesOut<*>) {
            out.clear()
            writeString(out, itemName)
            writeString(out, itemPart)
            out.writeInt(itemLevel)
            writeString(out, potentialGrade)
            writeStringList(out, potentialOptions)
            writeString(out, additionalPotentialGrade)
            writeStringList(out, additionalPotentialOptions)
            out.writeInt(targetStar)
            out.writeBoolean(isNoljang)
        }

        private fun writeString(out: BytesOut<*>, s: String?) {
            if (s == null) { out.writeInt(-1); return }
            val utf = s.toByteArray(Charsets.UTF_8)
            out.writeInt(utf.size)
            out.write(utf)
        }

        private fun writeStringList(out: BytesOut<*>, list: List<String?>?) {
            if (list == null) { out.writeInt(-1); return }
            out.writeInt(list.size)
            for (s in list) writeString(out, s)
        }
    }

    data class ComponentCosts(
        val blackCubeCost: Double?,
        val additionalCubeCost: Double?,
        val starforceCost: Double?,
    ) : BytesMarshallable {
        val hasAnyCost: Boolean = blackCubeCost != null || additionalCubeCost != null || starforceCost != null
        val totalCost: Double?
            get() = if (hasAnyCost) {
                (blackCubeCost ?: 0.0) + (additionalCubeCost ?: 0.0) + (starforceCost ?: 0.0)
            } else {
                null
            }

        override fun writeMarshallable(out: BytesOut<*>) {
            out.clear()
            writeNullableDouble(out, blackCubeCost)
            writeNullableDouble(out, additionalCubeCost)
            writeNullableDouble(out, starforceCost)
        }

        private fun writeNullableDouble(out: BytesOut<*>, v: Double?) {
            if (v == null) { out.writeBoolean(false); return }
            out.writeBoolean(true)
            out.writeDouble(v)
        }

        companion object {
            fun empty(): ComponentCosts = ComponentCosts(null, null, null)
        }
    }

    fun backend(): OffHeapCacheBackend<CacheKey, ComponentCosts> = backend

    fun stats(): String {
        val s = backend.stats()
        return "size=${s.size} hits=${s.hits} misses=${s.misses} hitRate=${"%.1f%%".format(s.hitRatePercent)}"
    }

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
        val cached = backend.get(key)
        if (cached != null) return cached
        val calculator = factory.createFullCalculator(input)
        val details = calculator.detailedCosts
        val value = ComponentCosts(
            blackCubeCost = details.blackCubeCost,
            additionalCubeCost = details.additionalCubeCost,
            starforceCost = details.starforceCost,
        )
        backend.put(key, value)
        return value
    }
}
