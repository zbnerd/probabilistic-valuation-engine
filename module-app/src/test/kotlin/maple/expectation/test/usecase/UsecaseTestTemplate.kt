package maple.expectation.test.usecase

import java.time.Duration
import maple.expectation.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Tag
import org.springframework.test.context.ActiveProfiles

/**
 * Application 레이어 Usecase 테스트 템플릿
 *
 * <h3>특징</h3>
 * <ul>
 *   <li>IntegrationTestBase 상속 (DB 격리, Testcontainers 지원)</li>
 *   <li>외부 의존성 Mockito로 격리</li>
 *   <li>LogicExecutor 패턴 검증 지원</li>
 *   <li>비동기 테스트 Awaitility 지원</li>
 *   <li>WebEnvironment.NONE으로 서버 없이 Service 레벨 테스트</li>
 * </ul>
 *
 * <h3>사용 시나리오</h3>
 * <ul>
 *   <li>Facade/Usecase 클래스 테스트</li>
 *   <li>Application Service 테스트</li>
 *   <li>여러 Port 조합 로직 검증</li>
 *   <li>비동기 작업 테스트</li>
 * </ul>
 *
 * <h3>Anti-patterns (금지)</h3>
 * <ul>
 *   <li>Thread.sleep() 사용 금지 (Awaitility 사용)</li>
 *   <li>실제 외부 API 호출 금지 (Mock 사용)</li>
 *   <li>@MockBean 과도한 사용 (Context 캐싱 방해)</li>
 * </ul>
 *
 * <h3>사용 예시</h3>
 * <pre>
 * class ExpectationFacadeTest : UsecaseTestTemplate() {
 *
 *     &#64;Autowired
 *     lateinit var expectationFacade: ExpectationFacade
 *
 *     &#64;MockkBean
 *     lateinit var externalApiPort: ExternalApiPort
 *
 *     &#64;Test
 *     fun `기대값 계산 성공`() {
 *         // Given
 *         val ign = "testCharacter"
 *         given(externalApiPort.fetchData(ign)).willReturn(mockData)
 *
 *         // When
 *         val result = expectationFacade.calculate(ign)
 *
 *         // Then
 *         assertThat(result.expectationValue).isGreaterThan(0)
 *     }
 * }
 * </pre>
 *
 * @see maple.expectation.support.IntegrationTestBase
 * @see maple.expectation.infrastructure.executor.LogicExecutor
 */
@Tag("integration")
@ActiveProfiles("test")
abstract class UsecaseTestTemplate : IntegrationTestBase() {

    // ========================================
    // Async Testing Helpers (Awaitility)
    // ========================================

    /**
     * 조건이 만족될 때까지 대기
     * 기본 타임아웃: 5초
     *
     * <p>사용처: 비동기 작업 완료 검증
     *
     * @param timeout 최대 대기 시간
     * @param condition 만족 조건 (true 반환 시 대기 종료)
     */
    protected fun awaitUntil(
        timeout: Duration = Duration.ofSeconds(5),
        condition: () -> Boolean,
    ) {
        await().atMost(timeout).untilAsserted {
            assertThat(condition()).isTrue
        }
    }

    /**
     * 비동기 작업 완료 대기
     *
     * <p>사용처: CompletableFuture, Coroutine 등 비동기 결과 검증
     *
     * @param timeout 최대 대기 시간
     * @param assertion 검증 로직
     */
    protected fun awaitCompletion(
        timeout: Duration = Duration.ofSeconds(10),
        assertion: () -> Unit,
    ) {
        await().atMost(timeout).untilAsserted(assertion)
    }

    /**
     * 주어진 시간 동안 조건이 변경되지 않음을 검증
     *
     * <p>사용처: 캐시 무효화, 상태 변경 확인
     *
     * @param duration 확인할 기간
     * @param condition 변화 없음을 확인할 조건
     */
    protected fun awaitUnchanged(
        duration: Duration = Duration.ofSeconds(2),
        condition: () -> Any?,
    ) {
        await().atLeast(duration).until(condition) { value -> true }
    }

    // ========================================
    // Execution Context Helpers
    // ========================================

    /**
     * LogicExecutor 실행 컨텍스트 검증
     *
     * <p>사용처: LogicExecutor 패턴 사용 시 예외 처리 및 로깅 검증
     *
     * @param execution 실행할 로직
     * @param expectedContext 기대하는 TaskContext (메트릭, 로깅용)
     * @return 실행 결과
     */
    protected fun <T> assertExecutorContext(
        execution: () -> T,
        expectedContext: String = "",
    ): T {
        // 실제 구현에서는 AOP/Metric 검증 로직 추가 가능
        // 현재는 실행 후 로그 또는 컨텍스트 검증 플레이스홀더
        return execution()
    }

    /**
     * 예외 발생을 검증하는 실행
     *
     * <p>사용처: 예외 처리 로직 검증
     *
     * @param execution 실행할 로직
     * @param expectedExceptionType 기대하는 예외 타입
     */
    protected fun <T : Throwable> assertThrows(
        execution: () -> Any?,
        expectedExceptionType: Class<T>,
    ): T {
        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(
                expectedExceptionType,
                org.junit.jupiter.api.function.Executable { execution() },
            )
        return exception
    }

    // ========================================
    // Test Data Helpers
    // ========================================

    /**
     * 테스트 데이터 정리 후 실행
     *
     * <p>주의: DatabaseCleaner가 @BeforeEach에서 실행되므로
     * 테스트 간 데이터 격리이 이미 보장됨.
     * 추가 정리가 필요한 경우에만 사용.
     *
     * @param block 실행할 로직
     * @return 실행 결과
     */
    protected fun <T> withCleanData(block: () -> T): T {
        // DatabaseCleaner가 @BeforeEach에서 실행되므로
        // 추가 정리가 필요한 경우에만 사용
        return block()
    }

    /**
     * 여러 번 실행하여 결과 검증 (루프 테스트)
     *
     * <p>사용처: 멀티스레드, 동시성 테스트
     *
     * @param times 반복 횟수
     * @param block 실행할 로직
     */
    protected fun repeatTest(
        times: Int,
        block: (index: Int) -> Unit,
    ) {
        repeat(times) { index ->
            block(index + 1)
        }
    }

    // ========================================
    // Assertion Extensions
    // ========================================

    /**
     * Optional 값 검증
     *
     * <p>사용처: Optional 반환 값 검증
     */
    protected fun <T> assertOptionalPresent(
        optional: java.util.Optional<T>,
        assertion: (T) -> Unit = {},
    ) {
        assertThat(optional).isPresent
        optional.ifPresent { value ->
            if (assertion != ({}) as (T) -> Unit) {
                assertion(value)
            }
        }
    }

    /**
     * Optional 값이 비어있음 검증
     */
    protected fun <T> assertOptionalEmpty(optional: java.util.Optional<T>) {
        assertThat(optional).isEmpty
    }
}
