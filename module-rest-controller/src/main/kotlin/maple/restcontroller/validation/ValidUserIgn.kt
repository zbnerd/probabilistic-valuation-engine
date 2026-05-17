package maple.restcontroller.validation

import jakarta.validation.Constraint
import jakarta.validation.Payload
import kotlin.annotation.AnnotationTarget.VALUE_PARAMETER
import kotlin.annotation.AnnotationRetention.RUNTIME

@Target(VALUE_PARAMETER)
@Retention(RUNTIME)
@Constraint(validatedBy = [UserIgnValidator::class])
annotation class ValidUserIgn(
    val message: String = "Invalid character name",
    val groups: Array<kotlin.reflect.KClass<*>> = [],
    val payload: Array<kotlin.reflect.KClass<out Payload>> = []
)
