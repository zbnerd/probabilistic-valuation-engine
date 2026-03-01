package maple.expectation.infrastructure.security.cors

import jakarta.validation.Constraint
import jakarta.validation.Payload

/**
 * CORS 오리진 유효성 검증 어노테이션
 *
 * <p>Issue #21: CORS 오리진 검증 강화
 *
 * <p>{@link CorsOriginValidator}를 사용하여 오리진 목록의 유효성을 검증합니다.
 *
 * <h4>사용 예시</h4>
 *
 * <pre>{@code
 * @ValidCorsOrigin
 * private List<String> allowedOrigins;
 * }</pre>
 *
 * @see CorsOriginValidator
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [CorsOriginConstraintValidator::class])
@MustBeDocumented
annotation class ValidCorsOrigin(
    val message: String = "유효하지 않은 CORS 오리진이 포함되어 있습니다.",
    val groups: Array<kotlin.reflect.KClass<*>> = [],
    val payload: Array<kotlin.reflect.KClass<out Payload>> = []
)
