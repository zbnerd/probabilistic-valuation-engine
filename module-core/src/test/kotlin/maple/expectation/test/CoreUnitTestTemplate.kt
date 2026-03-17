package maple.expectation.test

import net.jqwik.api.Property
import org.assertj.core.api.Assertions.assertThat

/**
 * CoreUnitTestTemplate
 *
 * Core 레이어 단위 테스트를 위한 템플릿 클래스입니다.
 * Spring Context를 로드하지 않아 빠른 테스트 실행이 가능합니다.
 *
 * 특징:
 * - Spring Context 로딩 없음 (빠른 실행, 일반적으로 < 100ms)
 * - 순수 Kotlin/Java 로직만 테스트
 * - Property-based testing 지원 (jqwik)
 * - Given-When-Then 패턴 헬퍼
 *
 * 사용 시나리오:
 * - Value Object 검증
 * - Domain Service 로직 테스트
 * - Parser/Converter 로직 테스트
 * - Business Rule 검증
 *
 * Anti-patterns (금지):
 * - @SpringBootTest 사용 금지
 * - Database 접근 금지
 * - External API 호출 금지
 *
 * @see CoreUnitTestTemplateExample for usage examples
 */
abstract class CoreUnitTestTemplate {

    // ========================================
    // Given-When-Then Pattern Helpers
    // ========================================

    /**
     * Given: 테스트 데이터/상태 준비
     *
     * 사용 예시:
     * val price = given { ItemPrice.of(1L, "아케인 심볼", 1000000) }
     */
    protected fun <T> given(block: () -> T): T = block()

    /**
     * When: 테스트 대상 로직 실행
     *
     * Note: Kotlin의 `when`은 예약어이므로 백틱 사용
     *
     * 사용 예시:
     * val result = `when` { price.isFreshWithinHours(24) }
     */
    protected fun <T> `when`(action: () -> T): T = action()

    /**
     * Then: 결과 검증
     *
     * 사용 예시:
     * then(result) { assertTrue(it) }
     */
    protected fun <T> then(result: T, assertion: (T) -> Unit) = assertion(result)

    // ========================================
    // Property-Based Testing Helpers
    // ========================================

    /**
     * Property-based test 실행 헬퍼
     *
     * jqwik의 @Property 애너테이션과 함께 사용합니다.
     * 무작위 입력값을 생성하여 불변성(invariant)을 검증합니다.
     *
     * 사용 예시:
     * ```kotlin
     * @Property
     * fun `모든 양수는 제곱해도 양수다`(@ForAll @PositiveInt value: Int) {
     *     assertTrue(value * value > 0)
     * }
     *
     * @Provide
     * fun positiveInts(): Arbitrary<Int> = Arbitraries.integers().between(1, 10000)
     * ```
     *
     * @see net.jqwik.api.Property
     * @see net.jqwik.api.ForAll
     * @see net.jqwik.api.Provide
     */

    // ========================================
    // Assertion Helpers
    // ========================================

    /**
     * 두 값이 같은지 검증
     */
    protected fun <T> assertEqual(expected: T, actual: T) {
        assertThat(actual).isEqualTo(expected)
    }

    /**
     * 조건이 참인지 검증
     */
    protected fun assertTrue(condition: Boolean, message: String = "") {
        assertThat(condition).`as`(message).isTrue
    }

    /**
     * 조건이 거짓인지 검증
     */
    protected fun assertFalse(condition: Boolean, message: String = "") {
        assertThat(condition).`as`(message).isFalse
    }

    /**
     * 값이 null이 아님을 검증
     */
    protected fun <T> assertNotNull(value: T?, message: String = "") {
        assertThat(value).`as`(message).isNotNull
    }

    /**
     * 값이 null임을 검증
     */
    protected fun <T> assertNull(value: T?, message: String = "") {
        assertThat(value).`as`(message).isNull()
    }

    /**
     * 예외 발생 검증
     *
     * 사용 예시:
     * val exception = assertThrows(IllegalArgumentException::class.java) {
     *     ItemPrice.of(-1L, "Invalid", 1000)
     * }
     * assertThat(exception.message).contains("must be positive")
     */
    protected fun <T : Throwable> assertThrows(
        exceptionClass: Class<T>,
        block: () -> Unit,
    ): T {
        val throwable = org.assertj.core.api.Assertions.catchThrowable(block)
        assertThat(throwable).isInstanceOf(exceptionClass)
        @Suppress("UNCHECKED_CAST")
        return throwable as T
    }
}
