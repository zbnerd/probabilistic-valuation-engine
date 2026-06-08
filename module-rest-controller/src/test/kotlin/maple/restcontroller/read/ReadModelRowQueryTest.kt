package maple.restcontroller.read

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadModelRowQueryTest {
    @Test
    fun `build throws on empty requests to avoid WHERE () syntax error`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ReadModelRowQuery.build(emptyMap())
        }
        assertTrue(ex.message!!.contains("requests"), "message: ${ex.message}")
    }

    @Test
    fun `build produces OR-chained pair predicates and indexed params`() {
        val result = ReadModelRowQuery.build(
            mapOf("f***l" to 1, "s***d" to 2),
        )
        val sql = result.first
        val params = result.second

        assertTrue(
            sql.contains("(user_ign = :userIgn0 AND preset_no = :presetNo0)"),
            "missing first predicate in: $sql",
        )
        assertTrue(
            sql.contains("(user_ign = :userIgn1 AND preset_no = :presetNo1)"),
            "missing second predicate in: $sql",
        )
        assertTrue(
            sql.contains(") OR ("),
            "missing OR between predicates in: $sql",
        )
        assertEquals(
            setOf("userIgn0", "presetNo0", "userIgn1", "presetNo1"),
            params.parameterNames.toSet(),
        )
        assertEquals("f***l", params.getValue("userIgn0"))
        assertEquals(1, params.getValue("presetNo0"))
        assertEquals("s***d", params.getValue("userIgn1"))
        assertEquals(2, params.getValue("presetNo1"))
    }
}
