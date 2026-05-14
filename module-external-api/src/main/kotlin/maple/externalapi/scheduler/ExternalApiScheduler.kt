package maple.externalapi.scheduler

import jakarta.annotation.PreDestroy
import maple.externalapi.cache.OcidCacheProvider
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

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ExternalApiScheduler(
    private val ocidLookupPhase: OcidLookupPhase,
    private val characterBasicPhase: CharacterBasicSnapshotPhase,
    private val itemEquipmentPhase: ItemEquipmentSnapshotPhase,
    private val ocidCacheProvider: OcidCacheProvider,
    @Value("\${external-api.schedule.run-on-startup:false}")
    private val runOnStartup: Boolean,
    @Value("\${external-api.schedule.skip-character-basic:false}")
    private val skipCharacterBasic: Boolean,
) {
    private val log = LoggerFactory.getLogger(ExternalApiScheduler::class.java)
    private val running = AtomicBoolean(false)
    private val shutdown = AtomicBoolean(false)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        ocidCacheProvider.refresh()
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
                ocidCacheProvider.refresh()
            } else {
                ocidLookupPhase.execute(executor)
                val cache = ocidCacheProvider.refresh()
                characterBasicPhase.execute(executor, cache)
            }
        } finally {
            running.set(false)
        }
    }

    private fun runItemEquipmentLoop() {
        log.info("[Scheduler] ITEM_EQUIPMENT continuous loop started")
        while (!shutdown.get()) {
            val entries = ocidCacheProvider.current().entries.toList()
            if (entries.isEmpty()) {
                log.warn("[Scheduler] OCID cache empty, waiting 30s")
                Thread.sleep(Duration.ofSeconds(30))
                ocidCacheProvider.refresh()
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
