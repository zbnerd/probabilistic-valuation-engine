package maple.expectation.web.validation

import jakarta.validation.Constraint
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * 메이플스토리 OCID 검증 어노테이션
 *
 * OCID 형식:
 * - 64자리 16진수 문자열 (SHA-256 해시)
 * - Nexon Open API에서 제공하는 고유 식별자
 *
 * @property message 에러 메시지
 * @property groups 검증 그룹
 * @property payload 페이로드
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [OcidValidator::class])
@MustBeDocumented
annotation class ValidOcid(
    val message: String = "OCID 형식이 올바르지 않습니다 (64자리 16진수)",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)
