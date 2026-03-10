package maple.expectation.web.validation

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext

/**
 * 메이플스토리 OCID 검증 validator
 *
 * 검증 규칙:
 * 1. 정확히 64자여야 함
 * 2. 16진수 문자열 (0-9, a-f, A-F)
 */
class OcidValidator : ConstraintValidator<ValidOcid, String> {

    companion object {
        private const val OCID_LENGTH = 64
        private val HEX_PATTERN = Regex("^[a-fA-F0-9]+$")
    }

    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext?
    ): Boolean {
        if (value.isNullOrBlank()) {
            return true // @NotBlank로 처리
        }

        val trimmed = value.trim()

        // 길이 검증
        if (trimmed.length != OCID_LENGTH) {
            context?.disableDefaultConstraintViolation()
            context?.buildConstraintViolationWithTemplate(
                "OCID는 $OCID_LENGTH 자여야 합니다 (현재: ${trimmed.length}자)"
            )?.addConstraintViolation()
            return false
        }

        // 16진수 패턴 검증
        if (!HEX_PATTERN.matches(trimmed)) {
            context?.disableDefaultConstraintViolation()
            context?.buildConstraintViolationWithTemplate(
                "OCID는 16진수 문자열이어야 합니다 (0-9, a-f, A-F만 허용)"
            )?.addConstraintViolation()
            return false
        }

        return true
    }
}
