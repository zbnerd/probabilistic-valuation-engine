package maple.restcontroller.read

import java.sql.Timestamp
import java.time.Instant

object StalenessCheck {
    fun partitionStale(
        rows: List<Map<String, Any?>>,
        minimumUpdatedAt: Instant?,
    ): Pair<List<Map<String, Any?>>, Int> {
        if (minimumUpdatedAt == null) return rows to 0
        val fresh = mutableListOf<Map<String, Any?>>()
        var stale = 0
        rows.forEach { row ->
            val updatedAt = (row["updated_at"] as? Timestamp)?.toInstant() ?: Instant.EPOCH
            if (updatedAt.isBefore(minimumUpdatedAt)) stale++ else fresh.add(row)
        }
        return fresh to stale
    }
}
