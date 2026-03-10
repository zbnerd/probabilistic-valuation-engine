package maple.expectation.web.validation

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext

/**
 * 메이플스토리 IGN (캐릭터 닉네임) 검증 validator
 *
 * 검증 규칙:
 * 1. 길이: 1-12자
 * 2. 허용 문자: 한글 (가-힣), 영문 (a-zA-Z), 숫자 (0-9)
 * 3. 공백 불가
 */
class IgnValidator : ConstraintValidator<ValidIgn, String> {

    companion object {
        private const val MIN_LENGTH = 1
        private const val MAX_LENGTH = 12
        private val VALID_PATTERN = Regex("^[가-힣a-zA-Z0-9]+$")
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
        if (trimmed.length < MIN_LENGTH || trimmed.length > MAX_LENGTH) {
            context?.disableDefaultConstraintViolation()
            context?.buildConstraintViolationWithTemplate(
                "캐릭터 닉네임은 $MIN_LENGTH-$MAX_LENGTH 자여야 합니다 (현재: ${trimmed.length}자)"
            )?.addConstraintViolation()
            return false
        }

        // 문자 패턴 검증
        if (!VALID_PATTERN.matches(trimmed)) {
            context?.disableDefaultConstraintViolation()
            context?.buildConstraintViolationWithTemplate(
                "캐릭터 닉네임은 한글, 영문, 숫자만 허용됩니다 (공백/특수문자 불가)"
            )?.addConstraintViolation()
            return false
        }

        return true
    }
}
