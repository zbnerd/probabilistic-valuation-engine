package maple.expectation.test

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag

/**
 * Infrastructure 레이어 Adapter 통합 테스트 템플릿
 *
 * <h3>특징</h3>
 * <ul>
 *   <li>Circuit Breaker 상태 검증</li>
 *   <li>Resilience4j 통합</li>
 *   <li>module-app의 TestcontainersConfiguration 재사용</li>
 * </ul>
 *
 * <h3>사용 시나리오</h3>
 * <ul>
 *   <li>Repository Adapter 테스트</li>
 *   <li>Cache Adapter 테스트</li>
 *   <li>Message Queue Adapter 테스트</li>
 * </ul>
 *
 * <h3>Testcontainers 설정</h3>
 * <p>이 템플릿은 module-app의 {@link maple.expectation.config.TestcontainersConfiguration}을
 * 재사용합니다. 별도의 컨테이너 설정이 필요하지 않습니다.</p>
 *
 * <h3>Anti-patterns (금지)</h3>
 * <ul>
 *   <li>실제 운영 환경 API 호출 금지</li>
 *   <li>Mock을 과도하게 사용 금지 (실제 컨테이너 사용)</li>
 * </ul>
 *
 * @see maple.expectation.config.TestcontainersConfiguration
 * @see maple.expectation.support.IntegrationTestBase
 */
@Tag("integration")
abstract class InfraAdapterTestTemplate {

    // ========================================
    // Test Lifecycle
    // ========================================

    @BeforeEach
    open fun setupInfrastructure() {
        // 하위 클래스에서 오버라이드하여 초기화 로직 추가
    }

    // ========================================
    // Circuit Breaker Helpers
    // ========================================

    /**
     * Circuit Breaker 상태 검증
     *
     * @param circuitBreaker 검증할 Circuit Breaker
     * @param expectedState 기대 상태 (OPEN, CLOSED, HALF_OPEN)
     */
    protected fun assertCircuitBreakerState(
        circuitBreaker: CircuitBreaker,
        expectedState: CircuitBreaker.State,
    ) {
        await().atMost(Duration.ofSeconds(5))
            .untilAsserted {
                assertThat(circuitBreaker.state).isEqualTo(expectedState)
            }
    }

    /**
     * Circuit Breaker가 OPEN 상태인지 검증
     */
    protected fun assertCircuitBreakerOpen(circuitBreaker: CircuitBreaker) {
        assertCircuitBreakerState(circuitBreaker, CircuitBreaker.State.OPEN)
    }

    /**
     * Circuit Breaker가 CLOSED 상태인지 검증
     */
    protected fun assertCircuitBreakerClosed(circuitBreaker: CircuitBreaker) {
        assertCircuitBreakerState(circuitBreaker, CircuitBreaker.State.CLOSED)
    }

    /**
     * Circuit Breaker가 HALF_OPEN 상태인지 검증
     */
    protected fun assertCircuitBreakerHalfOpen(circuitBreaker: CircuitBreaker) {
        assertCircuitBreakerState(circuitBreaker, CircuitBreaker.State.HALF_OPEN)
    }

    // ========================================
    // Awaitility Helpers
    // ========================================

    /**
     * 비동기 상태 변화 대기 및 검증
     *
     * <p>Thread.sleep() 대신 사용하여 타이밍 의존성 제거</p>
     *
     * @param timeoutMs 타임아웃 (밀리초)
     * @param assertion 검증 로직
     */
    protected fun awaitUntil(
        timeoutMs: Long = 5000,
        assertion: () -> Unit,
    ) {
        await().atMost(Duration.ofMillis(timeoutMs))
            .untilAsserted { assertion() }
    }

    /**
     * 조건이 참이 될 때까지 대기
     *
     * @param supplier 조건 확인 함수
     * @param expectedValue 기대값
     */
    protected fun awaitUntilEquals(
        supplier: () -> Boolean,
        expectedValue: Boolean = true,
        timeoutMs: Long = 5000,
    ) {
        await().atMost(Duration.ofMillis(timeoutMs))
            .until(supplier) { it == expectedValue }
    }
}
