package maple.expectation.config

import javax.sql.DataSource
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 데이터베이스 정리 유틸리티
 *
 * <h3>핵심 원칙</h3>
 *
 * <ul>
 *   <li>JDBC 직접 사용 - EntityManager 방식은 영속성 컨텍스트 상태에 영향받아 플래키 발생
 *   <li>FK 무시 모드 - session_replication_role = 'replica'
 *   <li>PGMQ 메타 테이블, Spring Batch, Flyway 내부 테이블 보호
 *   <li>@BeforeEach에서 호출 - @AfterEach는 테스트 실패 시 실행 안 될 수 있음
 *   <li>동적 테이블 지원 - clean() 호출 시마다 테이블 목록 새로고침
 * </ul>
 *
 * @see IntegrationTestBase
 */
@Component
@Profile("test")
class DatabaseCleaner(
    private val dataSource: DataSource,
) {

    /**
     * 모든 테이블 데이터 삭제
     *
     * <p>FK 제약 조건을 무시하고 TRUNCATE 실행.
     * 호출 시마다 테이블 목록을 새로 조회하여 동적 생성 테이블도 포함.
     */
    fun clean() {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { stmt ->
                // FK 무시 모드 활성화
                stmt.execute("SET session_replication_role = 'replica'")

                // 현재 존재하는 모든 테이블 조회 (동적 생성 테이블 포함)
                val rs = stmt.executeQuery(
                    """
                    SELECT tablename FROM pg_tables
                    WHERE schemaname = 'public'
                    AND tablename NOT LIKE 'pgmq\_%'  -- PGMQ 메타 테이블 제외
                    AND tablename NOT LIKE 'pg_%'     -- 시스템 테이블 제외
                    AND tablename NOT LIKE 'batch_%'  -- Spring Batch 메타 테이블 제외
                    AND tablename NOT LIKE 'flyway_%' -- Flyway 메타 테이블 제외
                    """.trimIndent(),
                )

                val tables = mutableListOf<String>()
                while (rs.next()) {
                    tables.add(rs.getString("tablename"))
                }
                rs.close()

                // 각 테이블 TRUNCATE
                tables.forEach { tableName ->
                    stmt.execute("TRUNCATE TABLE \"$tableName\" CASCADE")
                }

                // FK 무시 모드 비활성화 (origin = 기본 모드)
                stmt.execute("SET session_replication_role = 'origin'")
            }
            conn.commit()
        }
    }

    /**
     * 특정 테이블만 삭제
     */
    fun clean(vararg tables: String) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { stmt ->
                stmt.execute("SET session_replication_role = 'replica'")
                tables.forEach { tableName ->
                    stmt.execute("TRUNCATE TABLE \"$tableName\" CASCADE")
                }
                stmt.execute("SET session_replication_role = 'origin'")
            }
            conn.commit()
        }
    }
}
