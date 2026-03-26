package maple.expectation.test

import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 데이터베이스 정리 유틸리티 (test 프로필용)
 *
 * module-infra 전용. module-app의 config.DatabaseCleaner는
 * module-infra 클래스패스에 없으므로 별도로 정의.
 */
@Component
@Profile("test")
class DatabaseCleaner(
    private val dataSource: DataSource,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun clean() {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { stmt ->
                stmt.execute("SET session_replication_role = 'replica'")

                val rs = stmt.executeQuery(
                    """
                    SELECT tablename FROM pg_tables
                    WHERE schemaname = 'public'
                    AND tablename NOT LIKE 'pgmq\_%'
                    AND tablename NOT LIKE 'pg_%'
                    AND tablename NOT LIKE 'batch_%'
                    AND tablename NOT LIKE 'flyway_%'
                    """.trimIndent(),
                )

                val tables = mutableListOf<String>()
                while (rs.next()) {
                    tables.add(rs.getString("tablename"))
                }
                rs.close()

                tables.forEach { tableName ->
                    try {
                        stmt.execute("TRUNCATE TABLE \"$tableName\" CASCADE")
                    } catch (e: Exception) {
                        log.debug("Table '$tableName' does not exist yet, skipping: ${e.message}")
                    }
                }

                stmt.execute("SET session_replication_role = 'origin'")
            }
            conn.commit()
        }
    }

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
