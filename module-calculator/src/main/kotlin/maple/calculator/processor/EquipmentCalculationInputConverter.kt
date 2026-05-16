package maple.calculator.processor

import maple.calculator.model.CalculationResult
import maple.expectation.application.service.starforce.NoljangProbabilityTable
import maple.expectation.core.domain.equipment.SecondaryWeaponCategory
import maple.expectation.core.dto.cube.CubeCalculationInput
import maple.expectation.core.dto.v4.EquipmentCalculationInput

object EquipmentCalculationInputConverter {

    fun toCalculationInput(
        cubeInput: CubeCalculationInput,
        presetNo: Int,
    ): EquipmentCalculationInput {
        val potentialPart = SecondaryWeaponCategory.resolvePotentialPart(
            cubeInput.part, cubeInput.itemEquipmentPart,
        )
        return EquipmentCalculationInput.builder()
            .itemName(cubeInput.itemName ?: "")
            .itemPart(potentialPart)
            .itemEquipmentPart(cubeInput.itemEquipmentPart ?: "")
            .itemIcon(cubeInput.itemIcon ?: "")
            .itemLevel(cubeInput.level)
            .presetNo(presetNo)
            .isNoljang(cubeInput.isNoljangEquipment())
            .potentialGrade(cubeInput.grade)
            .potentialOptions(cubeInput.options?.filterNotNull())
            .additionalPotentialGrade(cubeInput.additionalGrade)
            .additionalPotentialOptions(cubeInput.additionalOptions?.filterNotNull())
            .currentStar(0)
            .targetStar(targetStar(cubeInput))
            .build()
    }

    fun toCalculationResult(
        ocid: String,
        presetNo: Int,
        cubeInput: CubeCalculationInput,
        componentCosts: CalculationCache.ComponentCosts,
        status: String,
        errorMessage: String?,
    ): CalculationResult = CalculationResult(
        ocid = ocid,
        presetNo = presetNo,
        itemName = cubeInput.itemName ?: "",
        itemLevel = cubeInput.level,
        itemPart = cubeInput.part,
        itemEquipmentPart = cubeInput.itemEquipmentPart,
        potentialGrade = cubeInput.grade,
        potentialOptions = cubeInput.options,
        additionalGrade = cubeInput.additionalGrade,
        additionalOptions = cubeInput.additionalOptions,
        currentStar = 0,
        targetStar = targetStar(cubeInput),
        status = status,
        totalCost = componentCosts.totalCost,
        blackCubeCost = componentCosts.blackCubeCost,
        additionalCubeCost = componentCosts.additionalCubeCost,
        starforceCost = componentCosts.starforceCost,
        errorMessage = errorMessage,
    )

    fun targetStar(cubeInput: CubeCalculationInput): Int {
        if (cubeInput.starforce <= 0 || cubeInput.itemName.isNullOrBlank() || cubeInput.level <= 0) return 0
        return if (cubeInput.isNoljangEquipment()) {
            minOf(cubeInput.starforce, NoljangProbabilityTable.MAX_NOLJANG_STAR)
        } else {
            cubeInput.starforce
        }
    }
}
