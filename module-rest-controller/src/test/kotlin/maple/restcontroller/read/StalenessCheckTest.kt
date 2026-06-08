package maple.restcontroller.read

import java.sql.Timestamp
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StalenessCheckTest {
    private val now = Instant.parse("2026-06-06T12:00:00Z")
    private val threshold = now.minusSeconds(60)

    @Test
    fun `partitionStale with null minimumUpdatedAt returns all rows and zero stale`() {
        val rows = listOf(
            mapOf<String, Any?>("updated_at" to Timestamp.from(now)),
            mapOf<String, Any?>("updated_at" to Timestamp.from(Instant.EPOCH)),
        )

        val result = StalenessCheck.partitionStale(rows, null)
        val fresh = result.first
        val stale = result.second

        assertEquals(2, fresh.size)
        assertEquals(0, stale)
    }

    @Test
    fun `partitionStale separates fresh from stale and counts them`() {
        val freshRow = mapOf<String, Any?>("updated_at" to Timestamp.from(now))
        val staleRow = mapOf<String, Any?>("updated_at" to Timestamp.from(Instant.EPOCH))

        val result = StalenessCheck.partitionStale(listOf(freshRow, staleRow), threshold)
        val fresh = result.first
        val stale = result.second

        assertEquals(1, fresh.size)
        assertEquals(freshRow, fresh[0])
        assertEquals(1, stale)
    }

    @Test
    fun `partitionStale treats missing updated_at as epoch and flags stale`() {
        val rowWithout = mapOf<String, Any?>("user_ign" to "f***l")

        val result = StalenessCheck.partitionStale(listOf(rowWithout), threshold)
        val fresh = result.first
        val stale = result.second

        assertTrue(fresh.isEmpty())
        assertEquals(1, stale)
    }
}
