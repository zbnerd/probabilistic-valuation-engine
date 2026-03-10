package maple.expectation.test

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Table
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 데이터베이스 정리 유틸리티
 *
 * <h3>규칙 준수 (Issue #547)</h3>
 *
 * <ul>
 *   <li>@BeforeEach에서 호출 - @AfterEach가 아님 (테스트 실패 시 오염 방지)
 *   <li>FK 무시하고 TRUNCATE - session_replication_role = 'replica'
 *   <li>PGMQ 내부 테이블 제외 - pgmq.* 테이블 건드리면 안 됨
 * </ul>
 *
 * <p>사용법:
 * <pre>
 * @BeforeEach
 * fun setUp() {
 *     databaseCleaner.clean()
 * }
 * </pre>
 */
@Component
@Profile("pgtest")
class DatabaseCleaner(
    @PersistenceContext private val em: EntityManager,
) : InitializingBean {

    private lateinit var tableNames: List<String>

    override fun afterPropertiesSet() {
        tableNames =
            em.metamodel.entities
                .filter { it.javaType.isAnnotationPresent(Table::class.java) }
                .mapNotNull {
                    it.javaType.getAnnotation(Table::class.java)?.name
                        ?: it.name.lowercase()
                }
                .filter { tableName ->
                    // PGMQ 내부 테이블은 건드리면 안 됨
                    !tableName.startsWith("pgmq") &&
                        !tableName.startsWith("pg_") &&
                        !tableName.startsWith("information_schema")
                }
    }

    /**
     * 모든 테이블 데이터 삭제
     *
     * <p>FK 제약 조건을 무시하고 TRUNCATE 실행
     */
    @Suppress("SqlSourceToSinkFlow")
    fun clean() {
        em.flush()

        // FK 무시 모드 활성화
        em.createNativeQuery("SET session_replication_role = 'replica'").executeUpdate()

        // 각 테이블 TRUNCATE
        tableNames.forEach { tableName ->
            em.createNativeQuery("TRUNCATE TABLE $tableName CASCADE").executeUpdate()
        }

        // FK 무시 모드 비활성화 (origin = 기본 모드)
        em.createNativeQuery("SET session_replication_role = 'origin'").executeUpdate()
    }

    /**
     * 특정 테이블만 삭제
     */
    @Suppress("SqlSourceToSinkFlow")
    fun clean(vararg tableNames: String) {
        em.flush()
        em.createNativeQuery("SET session_replication_role = 'replica'").executeUpdate()
        tableNames.forEach { tableName ->
            em.createNativeQuery("TRUNCATE TABLE $tableName CASCADE").executeUpdate()
        }
        em.createNativeQuery("SET session_replication_role = 'origin'").executeUpdate()
    }
}
