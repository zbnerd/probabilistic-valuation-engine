package maple.expectation.web.validation

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import maple.expectation.core.dto.v4.EquipmentCalculationInput

/**
 * 스타포스 범위 검증 validator
 *
 * 검증 규칙:
 * 1. currentStar: 0-25 범위
 * 2. targetStar: 0-25 범위
 * 3. targetStar >= currentStar
 */
class StarRangeValidator : ConstraintValidator<ValidStarRange, EquipmentCalculationInput> {

    override fun isValid(
        value: EquipmentCalculationInput?,
        context: ConstraintValidatorContext?,
    ): Boolean {
        if (value == null) {
            return true
        }

        val currentStar = value.currentStar
        val targetStar = value.targetStar

        // 범위 검증: 0-25
        if (currentStar < 0 || currentStar > 25) {
            context?.disableDefaultConstraintViolation()
            context?.buildConstraintViolationWithTemplate(
                "currentStar는 0~25 사이여야 합니다 (현재: $currentStar)",
            )?.addConstraintViolation()
            return false
        }

        if (targetStar < 0 || targetStar > 25) {
            context?.disableDefaultConstraintViolation()
            context?.buildConstraintViolationWithTemplate(
                "targetStar는 0~25 사이여야 합니다 (현재: $targetStar)",
            )?.addConstraintViolation()
            return false
        }

        // 순서 검증: targetStar >= currentStar
        if (targetStar < currentStar) {
            context?.disableDefaultConstraintViolation()
            context?.buildConstraintViolationWithTemplate(
                "targetStar는 currentStar보다 크거나 같아야 합니다 (current: $currentStar, target: $targetStar)",
            )?.addConstraintViolation()
            return false
        }

        return true
    }
}
