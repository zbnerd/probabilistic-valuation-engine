package maple.externalapi.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.scheduler.phase.CharacterBasicSnapshotPhase
import maple.externalapi.scheduler.phase.ItemEquipmentSnapshotPhase
import maple.externalapi.scheduler.phase.OcidLookupPhase
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ExternalApiScheduler(
    private val ocidLookupPhase: OcidLookupPhase,
    private val characterBasicPhase: CharacterBasicSnapshotPhase,
    private val itemEquipmentPhase: ItemEquipmentSnapshotPhase,
    private val artifactStore: ExternalApiArtifactStorePort,
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.schedule.run-on-startup:false}")
    private val runOnStartup: Boolean,
    @Value("\${external-api.schedule.skip-character-basic:false}")
    private val skipCharacterBasic: Boolean,
) {
    private val log = LoggerFactory.getLogger(ExternalApiScheduler::class.java)
    private val running = AtomicBoolean(false)
    private val shutdown = AtomicBoolean(false)
    private val ocidCache = AtomicReference<Map<String, String>>(emptyMap())
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        loadOcidCache()
        if (runOnStartup) {
            log.info("[Scheduler] run-on-startup enabled, triggering daily refresh")
            triggerDailyRefresh()
        }
        executor.submit { runItemEquipmentLoop() }
    }

    @Scheduled(cron = "\${external-api.schedule.daily-cron:0 0 3 * * *}")
    fun scheduledDailyRefresh() {
        triggerDailyRefresh()
    }

    fun triggerDailyRefresh() {
        if (!acquireLock(120_000)) {
            log.warn("[Scheduler] could not acquire lock for daily refresh, skipping")
            return
        }
        try {
            if (skipCharacterBasic) {
                log.info("[Scheduler] skip-character-basic enabled, loading OCID cache from existing data")
                loadOcidCache()
            } else {
                ocidLookupPhase.execute(executor)
                loadOcidCache()
                characterBasicPhase.execute(executor, ocidCache.get())
            }
        } finally {
            running.set(false)
        }
    }

    private fun runItemEquipmentLoop() {
        log.info("[Scheduler] ITEM_EQUIPMENT continuous loop started")
        while (!shutdown.get()) {
            val entries = ocidCache.get().entries.toList()
            if (entries.isEmpty()) {
                log.warn("[Scheduler] OCID cache empty, waiting 30s")
                Thread.sleep(Duration.ofSeconds(30))
                loadOcidCache()
                continue
            }
            if (!acquireLock(120_000)) {
                Thread.sleep(Duration.ofSeconds(5))
                continue
            }
            try {
                itemEquipmentPhase.execute(executor, entries)
            } finally {
                running.set(false)
            }
        }
        log.info("[Scheduler] ITEM_EQUIPMENT continuous loop stopped")
    }

    private fun loadOcidCache() {
        val keys = artifactStore.listStoredKeys(ExternalApiEndpoint.OCID_LOOKUP)
        if (keys.isEmpty()) {
            log.info("[Scheduler] no stored OCIDs found, cache empty")
            return
        }

        val cache = mutableMapOf<String, String>()
        for (key in keys) {
            try {
                val bytes = artifactStore.read(ExternalApiEndpoint.OCID_LOOKUP, key)
                if (bytes != null) {
                    val node = objectMapper.readTree(bytes)
                    val ocid = node.get("ocid")?.asText()
                    if (ocid != null) {
                        cache[key] = ocid
                    }
                }
            } catch (ex: Exception) {
                log.debug("[Scheduler] failed to parse OCID for key={}", key)
            }
        }
        ocidCache.set(cache)
        log.info("[Scheduler] OCID cache loaded: {} entries", cache.size)
    }

    private fun acquireLock(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (running.compareAndSet(false, true)) return true
            Thread.sleep(Duration.ofMillis(500))
        }
        return false
    }

    @PreDestroy
    fun onDestroy() {
        log.info("[Scheduler] shutdown requested")
        shutdown.set(true)
        executor.close()
    }
}
