package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import maple.expectation.util.GzipUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadModelDocumentExtractorTest {
    private val objectMapper = ObjectMapper()
    private val extractor = ReadModelDocumentExtractor(objectMapper)

    @Test
    fun `extract prefers JSON fields over DB row fallback`() {
        val json = """
            {
              "presetNo": 7,
              "summary": { "totalCost": 1234.5, "equipmentCount": 3 },
              "metadata": { "calculatedAt": "2026-06-06T11:00:00Z" },
              "equipment": [ { "name": "x" } ]
            }
        """.trimIndent()
        val compressed = GzipUtils.compress(json.toByteArray())
        val row = mapOf<String, Any?>(
            "user_ign" to "f***l",
            "preset_no" to 1,
            "total_cost" to BigDecimal("999.0"),
            "equipment_count" to 0,
            "calculated_at" to Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
        )

        val out = extractor.extract("f***l", compressed, row)

        assertEquals("f***l", out.userIgn)
        assertEquals(7, out.presetNo)
        assertEquals(BigDecimal("1234.5"), out.totalCost)
        assertEquals(3, out.equipmentCount)
        assertEquals(1, out.equipment.size)
        assertEquals(Instant.parse("2026-06-06T11:00:00Z"), out.calculatedAt)
    }

    @Test
    fun `extract falls back to DB row when JSON omits fields`() {
        val json = """{"equipment": []}"""
        val compressed = GzipUtils.compress(json.toByteArray())
        val row = mapOf<String, Any?>(
            "user_ign" to "s***d",
            "preset_no" to 42,
            "total_cost" to BigDecimal("500.0"),
            "equipment_count" to 4,
            "calculated_at" to Timestamp.from(Instant.parse("2026-05-01T00:00:00Z")),
        )

        val out = extractor.extract("s***d", compressed, row)

        assertEquals(42, out.presetNo)
        assertEquals(BigDecimal("500.0"), out.totalCost)
        assertEquals(4, out.equipmentCount)
        assertEquals(Instant.parse("2026-05-01T00:00:00Z"), out.calculatedAt)
        assertTrue(out.equipment.isEmpty())
    }

    @Test
    fun `extract returns defaults when both JSON and row lack a field`() {
        val json = """{}"""
        val compressed = GzipUtils.compress(json.toByteArray())
        val row = mapOf<String, Any?>(
            "user_ign" to "t***d",
            "preset_no" to 1,
        )

        val out = extractor.extract("t***d", compressed, row)

        assertEquals(BigDecimal.ZERO, out.totalCost)
        assertEquals(0, out.equipmentCount)
        assertNotNull(out.calculatedAt)
    }
}
