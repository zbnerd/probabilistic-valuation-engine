package maple.calculator.processor

import maple.calculator.model.CalculationResult
import maple.expectation.core.calculation.ValuationInput
import maple.expectation.core.calculation.ValuationResult
import maple.expectation.core.domain.equipment.SecondaryWeaponCategory
import maple.expectation.core.dto.cube.CubeCalculationInput
import maple.expectation.core.starforce.domain.NoljangProbabilityCalculator

object EquipmentCalculationInputConverter {

    fun toValuationInput(cubeInput: CubeCalculationInput): ValuationInput {
        val potentialPart = SecondaryWeaponCategory.resolvePotentialPart(
            cubeInput.part,
            cubeInput.itemEquipmentPart,
        )
        return ValuationInput(
            itemName = cubeInput.itemName ?: "",
            part = potentialPart,
            equipmentPart = cubeInput.itemEquipmentPart ?: "",
            itemLevel = cubeInput.level,
            currentStar = 0,
            targetStar = targetStar(cubeInput),
            noljang = cubeInput.isNoljangEquipment(),
            potentialGrade = cubeInput.grade,
            potentialOptions = cubeInput.options.orEmpty().filterNotNull().toList(),
            additionalGrade = cubeInput.additionalGrade,
            additionalOptions = cubeInput.additionalOptions.orEmpty().filterNotNull().toList(),
        )
    }

    fun toCalculationResult(
        ocid: String,
        presetNo: Int,
        cubeInput: CubeCalculationInput,
        valuationResult: ValuationResult,
        status: String,
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
        totalCost = valuationResult.costs.totalCost,
        blackCubeCost = valuationResult.costs.blackCubeCost,
        additionalCubeCost = valuationResult.costs.additionalCubeCost,
        starforceCost = valuationResult.costs.starforceCost,
        errorMessage = null,
    )

    fun toErrorResult(
        ocid: String,
        presetNo: Int,
        cubeInput: CubeCalculationInput,
        errorMessage: String,
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
        status = "ERROR",
        totalCost = null,
        blackCubeCost = null,
        additionalCubeCost = null,
        starforceCost = null,
        errorMessage = errorMessage,
    )

    fun targetStar(cubeInput: CubeCalculationInput): Int {
        if (cubeInput.starforce <= 0 || cubeInput.itemName.isNullOrBlank() || cubeInput.level <= 0) return 0
        return if (cubeInput.isNoljangEquipment()) {
            minOf(cubeInput.starforce, NoljangProbabilityCalculator.MAX_NOLJANG_STAR)
        } else {
            cubeInput.starforce
        }
    }
}
