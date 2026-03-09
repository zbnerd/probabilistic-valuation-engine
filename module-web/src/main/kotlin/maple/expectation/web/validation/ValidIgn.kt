package maple.expectation.web.validation

import jakarta.validation.Constraint
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * 메이플스토리 IGN (캐릭터 닉네임) 검증 어노테이션
 *
 * 메이플스토리 캐릭터 명명 규칙:
 * - 길이: 1-12자
 * - 허용 문자: 한글, 영문, 숫자
 * - 특수문자: 일부 허용 (공백 제외)
 * - 욕설 및 비속어 불가 (서버 사이드 검증)
 *
 * @property message 에러 메시지
 * @property groups 검증 그룹
 * @property payload 페이로드
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [IgnValidator::class])
@MustBeDocumented
annotation class ValidIgn(
    val message: String = "캐릭터 닉네임 형식이 올바르지 않습니다 (1-12자, 한글/영문/숫자만 허용)",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)
