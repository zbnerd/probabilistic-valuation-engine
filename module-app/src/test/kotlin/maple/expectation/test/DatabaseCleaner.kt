package maple.expectation.test

import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 데이터베이스 정리 유틸리티 (pgtest 프로필용)
 *
 * <h3>규칙 준수 (Issue #547)</h3>
 *
 * <ul>
 *   <li>@BeforeEach에서 호출 - @AfterEach가 아님 (테스트 실패 시 오염 방지)
 *   <li>FK 무시하고 TRUNCATE - session_replication_role = 'replica'
 *   <li>PGMQ 내부 테이블 제외 - pgmq.* 테이블 건드리면 안 됨
 *   <li>동적 테이블 조회 - 실제 존재하는 테이블만 TRUNCATE
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
    private val dataSource: DataSource,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 모든 테이블 데이터 삭제
     *
     * <p>FK 제약 조건을 무시하고 TRUNCATE 실행.
     * <p>호출 시마다 테이블 목록을 새로 조회하여 동적 생성 테이블도 포함.
     */
    @Suppress("SqlSourceToSinkFlow")
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

                // 각 테이블 TRUNCATE (테이블이 존재하는 경우에만)
                tables.forEach { tableName ->
                    try {
                        stmt.execute("TRUNCATE TABLE \"$tableName\" CASCADE")
                    } catch (e: Exception) {
                        // 테이블이 존재하지 않으면 무시 (create-drop이 아직 테이블을 생성하지 않은 경우)
                        log.debug("Table '$tableName' does not exist yet, skipping: ${e.message}")
                    }
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
    @Suppress("SqlSourceToSinkFlow")
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
