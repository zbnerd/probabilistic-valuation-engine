package maple.expectation.infrastructure.hotkey

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Hot Key Distributor
 *
 * <p>Distributes load for hot keys by creating multiple versions
 * and spreading requests across them (round-robin).
 *
 * <h4>Strategy</h4>
 * <ul>
 *   <li>Key versioning: hot_key:v1, hot_key:v2, hot_key:v3</li>
 *   <li>Round-robin distribution across versions</li>
 *   <li>Cooldown period (60s) after hot period ends</li>
 * </ul>
 *
 * @see ADR-005 Single Flight + Hot Key Strategy
 */
@Component
class HotKeyDistributor(
    private val hotKeyDetector: HotKeyDetector,
    private val executor: LogicExecutor,
    @Value("\${hotkey.version-count:3}") private val versionCount: Int,
    @Value("\${hotkey.cooldown-seconds:60}") private val cooldownSeconds: Int,
) {

    companion object {
        private val log = LoggerFactory.getLogger(HotKeyDistributor::class.java)
    }

    // Round-robin counters per key
    private val versionCounters = ConcurrentHashMap<String, AtomicInteger>()

    // Cooldown tracking
    private val cooldownUntil = ConcurrentHashMap<String, Instant>()

    /**
     * Get distributed key version for hot key handling
     *
     * <p>If key is hot, returns versioned key (e.g., "my_key:v2").
     * If key is not hot, returns original key.
     *
     * @param key Original cache key
     * @return Key to use (versioned if hot, original otherwise)
     */
    fun getDistributedKey(key: String): String {
        return executor.executeOrDefault(
            {
                // Check if in cooldown
                if (isInCooldown(key)) {
                    log.debug("[HotKeyDistributor] Key in cooldown: {}", key)
                    return@executeOrDefault key
                }

                // Check if hot
                if (!hotKeyDetector.isHotKey(key)) {
                    // Not hot, but was it recently hot?
                    if (versionCounters.containsKey(key)) {
                        startCooldown(key)
                    }
                    return@executeOrDefault key
                }

                // Hot key - get versioned key
                getVersionedKey(key)
            },
            key, // Default to original key on error
            TaskContext.of("HotKeyDistributor", "GetDistributed", key),
        )
    }

    /**
     * Get versioned key using round-robin
     *
     * @param key Original key
     * @return Versioned key (e.g., "my_key:v2")
     */
    private fun getVersionedKey(key: String): String {
        val counter = versionCounters.computeIfAbsent(key) { AtomicInteger(0) }
        val version = (counter.getAndIncrement() % versionCount) + 1

        val versionedKey = "$key:v$version"
        log.debug("[HotKeyDistributor] Distributed to version: {}", versionedKey)

        return versionedKey
    }

    /**
     * Start cooldown period for a key that is no longer hot
     *
     * @param key Cache key
     */
    private fun startCooldown(key: String) {
        val cooldownEnd = Instant.now().plusSeconds(cooldownSeconds.toLong())
        cooldownUntil[key] = cooldownEnd

        log.info("[HotKeyDistributor] Starting cooldown for key: {} (until {})", key, cooldownEnd)
    }

    /**
     * Check if key is in cooldown period
     *
     * @param key Cache key
     * @return true if in cooldown
     */
    private fun isInCooldown(key: String): Boolean {
        val cooldownEnd = cooldownUntil[key] ?: return false
        return Instant.now().isBefore(cooldownEnd)
    }

    /**
     * Periodic cleanup of expired cooldown entries (runs every 60 seconds)
     */
    @Scheduled(fixedRate = 60000)
    fun cleanupExpiredCooldowns() {
        executor.executeVoid({
            val now = Instant.now()
            val beforeCount = cooldownUntil.size

            // Remove expired cooldowns
            cooldownUntil.entries.removeIf { (_, end) -> now.isAfter(end) }

            // Clean up version counters for keys no longer hot and not in cooldown
            versionCounters.keys.removeIf { key ->
                !hotKeyDetector.isHotKey(key) && !isInCooldown(key)
            }

            val afterCount = cooldownUntil.size
            val cleanedCount = beforeCount - afterCount

            if (cleanedCount > 0) {
                log.info("[HotKeyDistributor] Cleaned up {} expired cooldown entries", cleanedCount)
            }
        }, TaskContext.of("HotKeyDistributor", "CleanupExpiredCooldowns"))
    }

    /**
     * Get distribution stats for monitoring
     *
     * @param key Cache key
     * @return Distribution stats (version counters, cooldown status)
     */
    fun getStats(key: String): DistributionStats = executor.executeOrDefault(
        {
            DistributionStats(
                key = key,
                isHot = hotKeyDetector.isHotKey(key),
                isInCooldown = isInCooldown(key),
                currentVersion = versionCounters[key]?.get() ?: 0,
                accessCount = hotKeyDetector.getAccessCount(key),
            )
        },
        DistributionStats(key, false, false, 0, 0),
        TaskContext.of("HotKeyDistributor", "GetStats", key),
    )

    /**
     * Force reset distribution for a key (admin operation)
     *
     * @param key Cache key
     */
    fun reset(key: String) {
        executor.executeVoid({
            versionCounters.remove(key)
            cooldownUntil.remove(key)
            log.info("[HotKeyDistributor] Reset distribution for key: {}", key)
        }, TaskContext.of("HotKeyDistributor", "Reset", key))
    }

    /**
     * Distribution statistics
     */
    data class DistributionStats(
        val key: String,
        val isHot: Boolean,
        val isInCooldown: Boolean,
        val currentVersion: Int,
        val accessCount: Long,
    )
}
