package maple.expectation.config

import jakarta.annotation.PostConstruct
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
 *   <li>PGMQ, Spring Batch, Flyway 내부 테이블 보호
 *   <li>@BeforeEach에서 호출 - @AfterEach는 테스트 실패 시 실행 안 될 수 있음
 * </ul>
 *
 * @see IntegrationTestBase
 */
@Component
@Profile("test")
class DatabaseCleaner(
    private val dataSource: DataSource,
) {

    private lateinit var tableNames: List<String>

    @PostConstruct
    fun init() {
        dataSource.connection.use { conn ->
            val rs = conn.metaData.getTables(null, "public", null, arrayOf("TABLE"))
            val tables = mutableListOf<String>()
            while (rs.next()) {
                val name = rs.getString("TABLE_NAME")
                // PGMQ 내부 테이블, Spring Batch 메타 테이블, Flyway 제외
                if (!name.startsWith("pgmq") &&
                    !name.startsWith("pg_") &&
                    !name.startsWith("batch_") &&
                    !name.startsWith("flyway_schema")
                ) {
                    tables.add(name)
                }
            }
            tableNames = tables
        }
    }

    /**
     * 모든 테이블 데이터 삭제
     *
     * <p>FK 제약 조건을 무시하고 TRUNCATE 실행
     */
    fun clean() {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { stmt ->
                // FK 무시 모드 활성화
                stmt.execute("SET session_replication_role = 'replica'")

                // 각 테이블 TRUNCATE
                tableNames.forEach { tableName ->
                    stmt.execute("TRUNCATE TABLE \"$tableName\" CASCADE")
                }

                // FK 무시 모드 비활성화
                stmt.execute("SET session_replication_role = 'DEFAULT'")
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
                stmt.execute("SET session_replication_role = 'DEFAULT'")
            }
            conn.commit()
        }
    }
}
