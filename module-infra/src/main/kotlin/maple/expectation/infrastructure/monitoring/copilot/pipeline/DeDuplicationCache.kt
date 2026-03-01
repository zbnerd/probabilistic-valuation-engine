package maple.expectation.infrastructure.monitoring.copilot.pipeline

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Component
@ConditionalOnProperty(name = ["monitoring.copilot.enabled"], havingValue = "true")
class DeDuplicationCache {
    companion object {
        private val log = LoggerFactory.getLogger(DeDuplicationCache::class.java)
    }

    @Value("\${monitoring.copilot.alert.throttle-window-ms:300000}")
    private var throttleWindowMs: Long = 300000

    private val recentIncidents = ConcurrentHashMap<String, Long>()

    fun isRecent(incidentId: String, now: Long): Boolean {
        val timestamp = recentIncidents[incidentId] ?: return false

        val age = now - timestamp
        return age < throttleWindowMs
    }

    fun track(incidentId: String, timestamp: Long) {
        recentIncidents[incidentId] = timestamp
        log.debug("[DeDuplicationCache] Tracked incident: {}", incidentId)
    }

    fun cleanOld(now: Int): Int {
        return cleanOld(now.toLong())
    }

    fun cleanOld(now: Long): Int {
        val threshold = now - throttleWindowMs

        val removedCount = AtomicInteger(0)
        recentIncidents.entries.removeIf { entry ->
            val isOld = entry.value < threshold
            if (isOld) {
                log.debug("[DeDuplicationCache] Cleaned old incident: {}", entry.key)
                removedCount.incrementAndGet()
            }
            isOld
        }

        val count = removedCount.get()
        if (count > 0) {
            log.debug("[DeDuplicationCache] Cleaned {} old incidents", count)
        }
        return count
    }

    fun size(): Int {
        return recentIncidents.size
    }

    fun clear(): Int {
        val size = recentIncidents.size
        recentIncidents.clear()
        log.info("[DeDuplicationCache] Cleared {} tracked incidents", size)
        return size
    }

    fun contains(incidentId: String): Boolean {
        return recentIncidents.containsKey(incidentId)
    }

    fun getThrottleWindowMs(): Long {
        return throttleWindowMs
    }
}
