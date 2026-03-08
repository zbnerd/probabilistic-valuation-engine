package maple.expectation.infrastructure.persistence.repository

import maple.expectation.domain.nexon.NexonApiCharacterData
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * Custom implementation fragment for NexonCharacterRepository batch operations.
 *
 * <p><strong>P1 Fix:</strong> Spring Data JPA's @Query with named parameters cannot properly bind
 * List&lt;Entity&gt; to individual named parameters in native INSERT statements. This
 * implementation uses JdbcTemplate.batchUpdate() for proper parameter binding and JDBC batch
 * optimization.
 *
 * <p><strong>Pattern:</strong> Spring Data JPA fragment implementation. The class name suffix
 * "Impl" is automatically detected by Spring Data, and methods are merged into the main repository.
 *
 * <p><strong>Performance:</strong> JDBC batch updates provide 90% reduction in DB round-trips
 * compared to individual inserts.
 *
 * <p><strong>P1-11 Multi-DataSource:</strong> Uses explicit `"transactionManager"` qualifier
 * to prevent ambiguity in multi-datasource environments (MongoDB read replicas).
 *
 * @see NexonCharacterRepositoryCustom#batchUpsert(List)
 * @see <a href="../../../../../docs/adr/013-multi-datasource-transaction-strategy.md">ADR-013: Multi-DataSource Transaction Strategy</a>
 */
class NexonCharacterRepositoryImpl(
    private val jdbcTemplate: JdbcTemplate,
) : NexonCharacterRepositoryCustom {

    @Transactional("transactionManager")
    override fun batchUpsert(dataList: List<NexonApiCharacterData>): Int {
        if (dataList.isEmpty()) {
            return 0
        }

        val sql =
            """
            INSERT INTO nexon_character_data (
                ocid, character_name, world_name, character_class, character_level,
                guild_name, character_image_url, date
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                character_name = VALUES(character_name),
                world_name = VALUES(world_name),
                character_class = VALUES(character_class),
                character_level = VALUES(character_level),
                guild_name = VALUES(guild_name),
                character_image_url = VALUES(character_image_url),
                date = VALUES(date)
            """.trimIndent()

        val results =
            jdbcTemplate.batchUpdate(
                sql,
                object : BatchPreparedStatementSetter {
                    override fun setValues(
                        ps: PreparedStatement,
                        i: Int,
                    ) {
                        val data = dataList[i]
                        ps.setString(1, data.ocid)
                        ps.setString(2, data.characterName)
                        ps.setString(3, data.worldName)
                        ps.setString(4, data.characterClass)
                        ps.setInt(5, data.characterLevel!!)
                        ps.setString(6, data.guildName)
                        ps.setString(7, data.characterImageUrl)
                        ps.setTimestamp(
                            8,
                            data.date?.let { java.sql.Timestamp.from(it) },
                        )
                    }

                    override fun getBatchSize(): Int = dataList.size
                },
            )

        // Sum all affected rows
        return results.sum()
    }
}
