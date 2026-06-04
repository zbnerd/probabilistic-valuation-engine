package maple.expectation.infrastructure.executor

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.Logger

class StepTimer(
    private val operation: String,
    private val thresholdMs: Long = 500,
    private val sampleRate: Double = 1.0,
    private val tags: Map<String, String> = emptyMap(),
) {
    private val startedAt = System.nanoTime()
    private val lastMark = AtomicLong(startedAt)
    private val steps = ConcurrentLinkedQueue<Pair<String, Long>>()

    fun mark(step: String) {
        val now = System.nanoTime()
        val elapsedMs = (now - lastMark.getAndSet(now)) / 1_000_000
        steps += step to elapsedMs
    }

    fun close(logger: Logger) {
        val totalMs = (System.nanoTime() - startedAt) / 1_000_000
        if (totalMs >= thresholdMs && (sampleRate >= 1.0 || ThreadLocalRandom.current().nextDouble() <= sampleRate)) {
            val stepsStr = steps.joinToString(" -> ") { "${it.first}:${it.second}ms" }
            if (tags.isNotEmpty()) {
                logger.info(
                    "[StepTrace] op={} total={}ms tags={} steps={}",
                    operation,
                    totalMs,
                    tags.entries.joinToString(",") { "${it.key}=${it.value}" },
                    stepsStr,
                )
            } else {
                logger.info("[StepTrace] op={} total={}ms steps={}", operation, totalMs, stepsStr)
            }
        }
    }
}
