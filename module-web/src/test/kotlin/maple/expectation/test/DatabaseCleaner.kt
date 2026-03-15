package maple.expectation.test

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Table
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 테스트용 데이터베이스 정리 도구
 *
 * <p>module-web 테스트 전용 복사본
 * <p>module-app의 IntegrationTestBase에 의존할 수 없으므로 독립적으로 구현
 *
 * <h3>사용 방법</h3>
 * <pre>
 * &#64;BeforeEach
 * fun setUp() {
 *     databaseCleaner.clean()
 * }
 * </pre>
 */
@Component
class DatabaseCleaner {

    @PersistenceContext
    private lateinit var em: EntityManager

    /**
     * 모든 테이블 삭제 (Constraint 무시)
     *
     * <p>순서:
     * <ol>
     *   <li>FK 제약 조건 비활성화</li>
     *   <li>모든 테이블 삭제</li>
     *   <li>FK 제약 조건 재활성화</li>
     * </ol>
     */
    @Transactional
    fun clean() {
        em.flush()
        em.clear()

        val entityManager = em
        val session = entityManager.delegate as org.hibernate.Session

        // FK 제약 조건 비활성화 (PostgreSQL)
        session.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate()
        session.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate()

        // 모든 JPA 테이블 삭제
        entityManager.metamodel.entities.forEach { entity ->
            val tableName = entity.javaType.getAnnotation(Table::class.java)?.name
                ?: entity.name.lowercase()
            session.createNativeQuery("TRUNCATE TABLE $tableName CASCADE").executeUpdate()
        }

        // FK 제약 조건 재활성화
        session.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate()
        session.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate()
    }
}
