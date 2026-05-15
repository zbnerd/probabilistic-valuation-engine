package maple.restcontroller.validation

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext

class UserIgnValidator : ConstraintValidator<ValidUserIgn, String> {

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value.isNullOrBlank()) return false
        if (value.length > 12) return false
        if (value.length < 2) return false
        return IGN_PATTERN.matches(value)
    }

    companion object {
        private val IGN_PATTERN = Regex("^[a-zA-Z0-9가-힣]+$")
    }
}
