package maple.synchronizer.repository

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class JdbcChunkedBatchExecutor {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> execute(
        label: String,
        itemLabel: String,
        runId: String,
        chunkId: String,
        items: List<T>,
        batchSize: Int,
        upsertBatch: (List<T>) -> Int,
    ): Int {
        val batches = items.chunked(batchSize)
        val totalStart = System.currentTimeMillis()
        log.info(
            "[{}] upsert start: {}={} batches={} batchSize={} : runId={} chunkId={}",
            label,
            itemLabel,
            items.size,
            batches.size,
            batchSize,
            runId,
            chunkId,
        )

        var totalAffected = 0
        batches.forEachIndexed { idx, batch ->
            val batchStart = System.currentTimeMillis()
            val affected = upsertBatch(batch)
            val batchMs = System.currentTimeMillis() - batchStart
            totalAffected += affected
            log.info(
                "[{}] upsert batch: batchNo={}/{} attempted={} affected={} durationMs={}",
                label,
                idx + 1,
                batches.size,
                batch.size,
                affected,
                batchMs,
            )
        }

        log.info(
            "[{}] upsert done: {}={} affected={} totalDurationMs={} : runId={} chunkId={}",
            label,
            itemLabel,
            items.size,
            totalAffected,
            System.currentTimeMillis() - totalStart,
            runId,
            chunkId,
        )
        return totalAffected
    }
}
