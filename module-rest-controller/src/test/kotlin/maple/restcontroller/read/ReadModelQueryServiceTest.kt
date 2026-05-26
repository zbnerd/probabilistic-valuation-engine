package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

class ReadModelQueryServiceTest {

    private val jdbc: NamedParameterJdbcTemplate = mock()
    private val objectMapper = ObjectMapper()
    private val service = ReadModelQueryService(jdbc, objectMapper)

    @Test
    fun `should return empty map for empty requests`() {
        val result = service.batchQuery(emptyMap())
        assertThat(result).isEmpty()
    }

    @Test
    fun `should decompress and parse read model rows`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val docJson = objectMapper.writeValueAsBytes(mapOf(
            "presetNo" to 1,
            "summary" to mapOf("totalCost" to 1000, "equipmentCount" to 5),
            "equipment" to listOf(mapOf("name" to "sword", "value" to 500)),
            "metadata" to mapOf("calculatedAt" to now.toString())
        ))
        val compressed = GzipUtils.compress(String(docJson))

        whenever(jdbc.queryForList(any<String>(), any<MapSqlParameterSource>()))
            .thenReturn(listOf(
                mapOf<String, Any>(
                    "user_ign" to "아델",
                    "preset_no" to 1,
                    "document" to compressed,
                    "total_cost" to BigDecimal(1000),
                    "equipment_count" to 5,
                    "calculated_at" to Timestamp.from(now),
                    "updated_at" to Timestamp.from(now),
                )
            ))

        val requests = mapOf("아델" to 1)
        val result = service.batchQuery(requests)

        assertThat(result).hasSize(1)
        assertThat(result).containsKey("아델")

        val response = result["아델"]!!
        assertThat(response.userIgn).isEqualTo("아델")
        assertThat(response.presetNo).isEqualTo(1)
        assertThat(response.totalCost).isEqualByComparingTo(BigDecimal(1000))
        assertThat(response.equipmentCount).isEqualTo(5)
        assertThat(response.equipment).hasSize(1)
        assertThat(response.calculatedAt).isEqualTo(now)
    }

    @Test
    fun `should return empty when no rows match`() {
        whenever(jdbc.queryForList(any<String>(), any<MapSqlParameterSource>()))
            .thenReturn(emptyList())

        val requests = mapOf("존재하지않는닉네임" to 1)
        val result = service.batchQuery(requests)

        assertThat(result).isEmpty()
    }

    @Test
    fun `should handle multiple users`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val doc1 = objectMapper.writeValueAsBytes(mapOf(
            "presetNo" to 1,
            "summary" to mapOf("totalCost" to 1000, "equipmentCount" to 3),
            "equipment" to emptyList<Any>(),
            "metadata" to mapOf("calculatedAt" to now.toString())
        ))
        val doc2 = objectMapper.writeValueAsBytes(mapOf(
            "presetNo" to 2,
            "summary" to mapOf("totalCost" to 2000, "equipmentCount" to 6),
            "equipment" to emptyList<Any>(),
            "metadata" to mapOf("calculatedAt" to now.toString())
        ))

        whenever(jdbc.queryForList(any<String>(), any<MapSqlParameterSource>()))
            .thenReturn(listOf(
                mapOf<String, Any>(
                    "user_ign" to "아델",
                    "preset_no" to 1,
                    "document" to GzipUtils.compress(String(doc1)),
                    "total_cost" to BigDecimal(1000),
                    "equipment_count" to 3,
                    "calculated_at" to Timestamp.from(now),
                    "updated_at" to Timestamp.from(now),
                ),
                mapOf<String, Any>(
                    "user_ign" to "진격캐넌",
                    "preset_no" to 2,
                    "document" to GzipUtils.compress(String(doc2)),
                    "total_cost" to BigDecimal(2000),
                    "equipment_count" to 6,
                    "calculated_at" to Timestamp.from(now),
                    "updated_at" to Timestamp.from(now),
                )
            ))

        val requests = mapOf("아델" to 1, "진격캐넌" to 2)
        val result = service.batchQuery(requests)

        assertThat(result).hasSize(2)
        assertThat(result["아델"]!!.presetNo).isEqualTo(1)
        assertThat(result["진격캐넌"]!!.presetNo).isEqualTo(2)
        assertThat(result["진격캐넌"]!!.totalCost).isEqualByComparingTo(BigDecimal(2000))
    }

    @Test
    fun `should treat stale updatedAt as cache miss`() {
        val now = Instant.now()
        val docJson = objectMapper.writeValueAsBytes(mapOf(
            "presetNo" to 1,
            "summary" to mapOf("totalCost" to 1000, "equipmentCount" to 5),
            "equipment" to emptyList<Any>(),
            "metadata" to mapOf("calculatedAt" to now.toString())
        ))

        whenever(jdbc.queryForList(any<String>(), any<MapSqlParameterSource>()))
            .thenReturn(listOf(
                mapOf<String, Any>(
                    "user_ign" to "아델",
                    "preset_no" to 1,
                    "document" to GzipUtils.compress(String(docJson)),
                    "total_cost" to BigDecimal(1000),
                    "equipment_count" to 5,
                    "calculated_at" to Timestamp.from(now),
                    "updated_at" to Timestamp.from(now.minus(Duration.ofMinutes(31))),
                )
            ))

        val result = service.batchQuery(mapOf("아델" to 1), Duration.ofMinutes(30))

        assertThat(result).isEmpty()
    }
}
