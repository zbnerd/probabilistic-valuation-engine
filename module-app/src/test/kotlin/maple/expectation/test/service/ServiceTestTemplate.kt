package maple.expectation.test.service

import jakarta.persistence.EntityManager
import maple.expectation.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * Domain Service 테스트 템플릿
 *
 * <h3>특징</h3>
 * <ul>
 *   <li>IntegrationTestBase 상속 (DB 격리)</li>
 *   <li>@Transactional 롤백 지원 (같은 스레드에서 실행)</li>
 *   <li>flushAndClear()로 영속성 컨텍스트 제어</li>
 *   <li>Domain Service 로직 검증에 최적화</li>
 *   <li>WebEnvironment.NONE으로 서버 없이 테스트</li>
 * </ul>
 *
 * <h3>UsecaseTestTemplate와의 차이</h3>
 * <table border="1">
 *   <tr><th>템플릿</th><th>용도</th><th>@Transactional</th><th>주요 대상</th></tr>
 *   <tr><td>UsecaseTestTemplate</td><td>Facade/Application Service</td><td>지원 안 함</td><td>Port 조합, 외부 연동</td></tr>
 *   <tr><td>ServiceTestTemplate</td><td>Domain Service</td><td>지원 (@Transactional 테스트)</td><td>도메인 로직, JPA</td></tr>
 * </table>
 *
 * <h3>사용 시나리오</h3>
 * <ul>
 *   <li>Domain Service 단위 테스트</li>
 *   <li>JPA 영속성 로직 검증</li>
 *   <li>@Transactional 동작 확인</li>
 *   <li>영속성 컨텍스트 제어가 필요한 테스트</li>
 * </ul>
 *
 * <h3>사용 예시</h3>
 * <pre>
 * &#64;Transactional
 * class CalculationServiceTest : ServiceTestTemplate() {
 *
 *     &#64;Autowired
 *     lateinit var calculationService: CalculationService
 *
 *     &#64;Autowired
 *     lateinit var characterRepository: CharacterRepository
 *
 *     &#64;Test
 *     fun `캐릭터 기대값 계산 후 저장`() {
 *         // Given
 *         val character = Character(ign = "test", level = 250)
 *         characterRepository.save(character)
 *
 *         // When
 *         calculationService.calculateExpectation(character.id)
 *
 *         // Then - DB에 실제로 반영됨을 확인
 *         flushAndClear()
 *         val saved = characterRepository.findById(character.id)
 *         assertThat(saved).isPresent
 *         assertThat(saved.get().expectationValue).isGreaterThan(0)
 *     }
 * }
 * </pre>
 *
 * <h3>주의사항</h3>
 * <ul>
 *   <li>@Transactional은 같은 스레드에서만 롤백됨</li>
 *   <li>@Async 메서드는 롤백되지 않음 (별도 처리 필요)</li>
 *   <li>flushAndClear() 후에 실제 DB 제약 조건 검증 가능</li>
 * </ul>
 *
 * @see maple.expectation.support.IntegrationTestBase
 * @see maple.expectation.test.usecase.UsecaseTestTemplate
 */
@Tag("integration")
@ActiveProfiles("test")
abstract class ServiceTestTemplate : IntegrationTestBase() {

    @Autowired
    override lateinit var entityManager: EntityManager

    /**
     * 각 테스트 전 추가 설정
     *
     * <p>IntegrationTestBase의 @BeforeEach에서 DatabaseCleaner가 실행되므로
     * 여기서는 추가 설정만 수행
     */
    @BeforeEach
    fun setUpServiceTest() {
        // IntegrationTestBase에서 DatabaseCleaner.clean() 실행됨
        // 여기서는 추가 설정이 필요한 경우에만 코드 추가
    }

    // ========================================
    // Persistence Context Helpers
    // ========================================

    /**
     * flush + clear 후 실행
     *
     * <p>flush: DB에 실제로 쓰기 → 제약 조건 검증
     * <p>clear: 1차 캐시 비우기 → DB에서 실제로 읽기
     *
     * <p>사용처: 영속성 컨텍스트를 통한 실제 DB 반영 검증
     *
     * @param block 실행할 로직
     * @return 실행 결과
     */
    protected fun <T> flushAndClear(block: () -> T): T {
        entityManager.flush()
        entityManager.clear()
        return block()
    }

    /**
     * flush만 실행 (1차 캐시 유지)
     *
     * <p>사용처: 제약 조건 위반 검증 후 동일 객체 계속 사용
     */
    protected fun flushOnly() {
        entityManager.flush()
    }

    /**
     * clear만 실행 (DB 미반영 상태로 캐시만 비움)
     *
     * <p>사용처: detached 상태 테스트
     */
    protected fun clearOnly() {
        entityManager.clear()
    }

    // ========================================
    // Repository Testing Helpers
    // ========================================

    /**
     * 엔티티 저장 후 ID 반환
     *
     * <p>사용처: 테스트 데이터 생성
     *
     * @param entity 저장할 엔티티
     * @return 저장된 엔티티 (ID 할당됨)
     */
    protected fun <T> persistAndFlush(
        entity: T,
    ): T {
        entityManager.persist(entity)
        entityManager.flush()
        return entity
    }

    /**
     * 엔티티 저장 후 clear (detached 상태)
     *
     * <p>사용처: 실제 DB 저장 후 캐시 분리 테스트
     *
     * @param entity 저장할 엔티티
     * @return detached 상태의 엔티티
     */
    protected fun <T> persistFlushAndClear(
        entity: T,
    ): T {
        entityManager.persist(entity)
        entityManager.flush()
        entityManager.clear()
        return entity
    }

    /**
     * DB에서 실제로 조회하여 검증
     *
     * <p>사용처: 1차 캐시를 통한 실제 DB 조회 검증
     *
     * @param entityClass 엔티티 클래스
     * @param id 엔티티 ID
     * @return 조회된 엔티티 또는 null
     */
    protected fun <T> findFromDb(
        entityClass: Class<T>,
        id: Any,
    ): T? {
        clearOnly() // 1차 캐시 비우기
        return entityManager.find(entityClass, id)
    }

    // ========================================
    // Transactional Testing Helpers
    // ========================================

    /**
     * 트랜잭션 내에서 실행 (rollback 대상)
     *
     * <p>사용처: 테스트용 데이터 생성 후 자동 정리
     *
     * @param block 실행할 로직
     * @return 실행 결과
     */
    @Transactional
    protected fun <T> withinTransaction(block: () -> T): T = block()

    /**
     * 트랜잭션 없이 실행 (commit 필요 시)
     *
     * <p>주의: 테스트 간 데이터 격리이 깨질 수 있으므로
     * DatabaseCleaner로 반드시 정리해야 함
     *
     * @param block 실행할 로직
     * @return 실행 결과
     */
    protected fun <T> withoutTransaction(block: () -> T): T = block()

    // ========================================
    // Assertion Extensions
    // ========================================

    /**
     * 엔티티 영속 상태 검증
     *
     * <p>사용처: 엔티티가 1차 캐시에 있는지 확인
     */
    protected fun <T> assertPersistent(
        entity: T,
    ) {
        assertThat(entityManager.contains(entity)).isTrue
    }

    /**
     * 엔티티 detached 상태 검증
     *
     * <p>사용처: 엔티티가 1차 캐시에서 분리되었는지 확인
     */
    protected fun <T> assertDetached(
        entity: T,
    ) {
        assertThat(entityManager.contains(entity)).isFalse
    }

    /**
     * DB에 실제로 저장되었는지 검증
     *
     * <p>사용처: flush 후 DB 조회로 실제 저장 확인
     *
     * @param entityClass 엔티티 클래스
     * @param id 엔티티 ID
     */
    protected fun <T> assertExistsInDb(
        entityClass: Class<T>,
        id: Any,
    ) {
        clearOnly()
        val entity = entityManager.find(entityClass, id)
        assertThat(entity).isNotNull
    }

    /**
     * DB에 존재하지 않음을 검증
     *
     * <p>사용처: 삭제 또는 rollback 확인
     *
     * @param entityClass 엔티티 클래스
     * @param id 엔티티 ID
     */
    protected fun <T> assertNotExistsInDb(
        entityClass: Class<T>,
        id: Any,
    ) {
        clearOnly()
        val entity = entityManager.find(entityClass, id)
        assertThat(entity).isNull()
    }
}
