package maple.expectation.web.dto.v4

import maple.expectation.web.dto.CubeCalculationInput

/**
 * V4 장비 기대값 계산 입력 DTO (#240)
 */
data class EquipmentCalculationInput(
    val itemName: String,
    val itemPart: String,
    val itemEquipmentPart: String,
    val itemIcon: String,
    val itemLevel: Int,
    val presetNo: Int,
    val isNoljang: Boolean,
    val potentialGrade: String?,
    val potentialOptions: List<String>?,
    val additionalPotentialGrade: String?,
    val additionalPotentialOptions: List<String>?,
    val currentStar: Int,
    val targetStar: Int,
) {
    fun hasPotential(): Boolean = !potentialGrade.isNullOrEmpty()
    fun hasAdditionalPotential(): Boolean = !additionalPotentialGrade.isNullOrEmpty()
    fun hasStarforce(): Boolean = currentStar < targetStar

    fun toPotentialCubeInput(): CubeCalculationInput {
        val input = CubeCalculationInput()
        input.itemName = itemName
        input.part = itemPart
        input.level = itemLevel
        input.grade = potentialGrade
        potentialOptions?.let { input.options.addAll(it) }
        return input
    }

    fun toAdditionalCubeInput(): CubeCalculationInput {
        val input = CubeCalculationInput()
        input.itemName = itemName
        input.part = itemPart
        input.level = itemLevel
        input.grade = additionalPotentialGrade
        additionalPotentialOptions?.let { input.options.addAll(it) }
        return input
    }

    fun isReady(): Boolean = itemName.isNotEmpty() && itemLevel > 0

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder {
        private var itemName: String = ""
        private var itemPart: String = ""
        private var itemEquipmentPart: String = ""
        private var itemIcon: String = ""
        private var itemLevel: Int = 0
        private var presetNo: Int = 0
        private var isNoljang: Boolean = false
        private var potentialGrade: String? = null
        private var potentialOptions: List<String>? = null
        private var additionalPotentialGrade: String? = null
        private var additionalPotentialOptions: List<String>? = null
        private var currentStar: Int = 0
        private var targetStar: Int = 0

        fun itemName(itemName: String) = apply { this.itemName = itemName }
        fun itemPart(itemPart: String) = apply { this.itemPart = itemPart }
        fun itemEquipmentPart(itemEquipmentPart: String) = apply { this.itemEquipmentPart = itemEquipmentPart }
        fun itemIcon(itemIcon: String) = apply { this.itemIcon = itemIcon }
        fun itemLevel(itemLevel: Int) = apply { this.itemLevel = itemLevel }
        fun presetNo(presetNo: Int) = apply { this.presetNo = presetNo }
        fun isNoljang(isNoljang: Boolean) = apply { this.isNoljang = isNoljang }
        fun potentialGrade(potentialGrade: String?) = apply { this.potentialGrade = potentialGrade }
        fun potentialOptions(potentialOptions: List<String>?) = apply { this.potentialOptions = potentialOptions }
        fun additionalPotentialGrade(additionalPotentialGrade: String?) = apply { this.additionalPotentialGrade = additionalPotentialGrade }
        fun additionalPotentialOptions(additionalPotentialOptions: List<String>?) = apply { this.additionalPotentialOptions = additionalPotentialOptions }
        fun currentStar(currentStar: Int) = apply { this.currentStar = currentStar }
        fun targetStar(targetStar: Int) = apply { this.targetStar = targetStar }

        fun build() = EquipmentCalculationInput(
            itemName, itemPart, itemEquipmentPart, itemIcon, itemLevel, presetNo, isNoljang,
            potentialGrade, potentialOptions, additionalPotentialGrade, additionalPotentialOptions,
            currentStar, targetStar,
        )
    }
}
