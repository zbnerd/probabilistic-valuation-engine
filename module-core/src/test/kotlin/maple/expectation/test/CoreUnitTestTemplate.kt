package maple.expectation.test

import org.assertj.core.api.Assertions.assertThat

/**
 * Core 레이어 단위 테스트 템플릿
 *
 * <h3>특징</h3>
 * <ul>
 *   <li>Spring Context 로딩 없음 (빠른 실행)</li>
 *   <li>순수 Kotlin/Java 로직만 테스트</li>
 *   <li>Property-based testing 지원 (jqwik)</li>
 * </ul>
 *
 * <h3>사용 시나리오</h3>
 * <ul>
 *   <li>Value Object 검증</li>
 *   <li>Domain Service 로직 테스트</li>
 *   <li>Parser/Converter 로직 테스트</li>
 *   <li>Business Rule 검증</li>
 * </ul>
 *
 * <h3>Anti-patterns (금지)</h3>
 * <ul>
 *   <li>@SpringBootTest 사용 금지</li>
 *   <li>Database 접근 금지</li>
 *   <li>External API 호출 금지</li>
 * </ul>
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
     * <h3>사용 예시</h3>
     * <pre>
     * val price = given { ItemPrice.of(1L, "아케인 심볼", 1000000) }
     * </pre>
     */
    protected fun <T> given(block: () -> T): T = block()

    /**
     * When: 테스트 대상 로직 실행
     *
     * <p>Note: Kotlin의 `when`은 예약어이므로 백틱 사용
     *
     * <h3>사용 예시</h3>
     * <pre>
     * val result = `when` { price.isFreshWithinHours(24) }
     * </pre>
     */
    protected fun <T> `when`(action: () -> T): T = action()

    /**
     * Then: 결과 검증
     *
     * <h3>사용 예시</h3>
     * <pre>
     * then(result) { assertTrue(it) }
     * </pre>
     */
    protected fun <T> then(result: T, assertion: (T) -> Unit) = assertion(result)

    // ========================================
    // Property-Based Testing Helpers
    // ========================================

    /**
     * Property-based test 실행 헬퍼
     *
     * <p>jqwik를 활용한 무작위 입력 기반 테스트를 위한 헬퍼 메서드.
     * jqwik @Property 애너테이션과 @ForAll을 사용하는 것이 권장됩니다.
     *
     * <h3>권장 사용법 (jqwik 직접 사용)</h3>
     * <pre>
     * import net.jqwik.api.Property
     * import net.jqwik.api.ForAll
     * import net.jqwik.api.Arbitrary
     * import net.jqwik.api.Provide
     * import net.jqwik.api.Arbitraries
     *
     * &#64;Property
     * fun addition_is_commutative(&#64;ForAll("positiveInts") x: Int) {
     *     // 테스트 로직
     * }
     *
     * companion object {
     *     &#64;Provide
     *     fun positiveInts(): Arbitrary<Int> = Arbitraries.integers().between(0, 1000)
     * }
     * </pre>
     *
     * @param generator jqwik Arbitrary 생성기 (향후 확장용)
     * @param test 검증 함수 (향후 확장용)
     */
    protected fun <T> propertyTest(
        generator: Any,
        test: Any,
    ): Nothing = throw NotImplementedError(
        "Property-based testing은 jqwik의 @Property와 @ForAll 애너테이션을 직접 사용하세요. " +
            "CoreUnitTestTemplateExample.kt의 예제를 참조하세요.",
    )

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
     * 예외 발생 검증
     *
     * <h3>사용 예시</h3>
     * <pre>
     * val exception = assertThrows(IllegalArgumentException::class.java) {
     *     ItemPrice.of(-1L, "Invalid", 1000)
     * }
     * assertThat(exception.message).contains("must be positive")
     * </pre>
     */
    protected fun <T : Throwable> assertThrows(
        exceptionClass: Class<T>,
        block: () -> Unit,
    ): T = org.assertj.core.api.Assertions.assertThatThrownBy(block)
        .isInstanceOf(exceptionClass)
        .let { exceptionClass.cast(it) }
}
