package maple.expectation.web.validation

import jakarta.validation.Constraint
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * 스타포스 범위 검증 어노테이션
 *
 * targetStar가 currentStar보다 크거나 같아야 하며,
 * 둘 다 0~25 범위 내에 있어야 합니다.
 *
 * @property message 에러 메시지
 * @property groups 검증 그룹
 * @property payload 페이로드
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [StarRangeValidator::class])
@MustBeDocumented
annotation class ValidStarRange(
    val message: String = "스타포스 범위가 유효하지 않습니다 (currentStar: 0-25, targetStar: 0-25, targetStar >= currentStar)",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)
