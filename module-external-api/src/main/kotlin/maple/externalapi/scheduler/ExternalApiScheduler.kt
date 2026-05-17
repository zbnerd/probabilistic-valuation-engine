package maple.externalapi.scheduler

import jakarta.annotation.PreDestroy
import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.SnapshotFetchPhase
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
    private val snapshotFetchPhase: SnapshotFetchPhase,
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
        if (skipCharacterBasic) {
            log.info("[Scheduler] skip-character-basic enabled, loading OCID cache from existing data")
            ocidCacheProvider.refresh()
            running.set(false)
            return
        }

        ocidLookupPhase.execute(executor)
            .thenCompose {
                val cache = ocidCacheProvider.refresh()
                snapshotFetchPhase.executeCharacterBasic(executor, cache)
            }
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("[Scheduler] daily refresh failed", ex)
                }
                running.set(false)
            }
    }

    private fun runItemEquipmentLoop() {
        log.info("[Scheduler] ITEM_EQUIPMENT continuous loop started")
        runItemEquipmentCycle()
    }

    private fun runItemEquipmentCycle() {
        if (shutdown.get()) {
            log.info("[Scheduler] ITEM_EQUIPMENT continuous loop stopped")
            return
        }

        val entries = ocidCacheProvider.current().entries.toList()
        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, waiting 30s")
            executor.submit {
                Thread.sleep(Duration.ofSeconds(30))
                ocidCacheProvider.refresh()
                runItemEquipmentCycle()
            }
            return
        }

        if (!acquireLock(120_000)) {
            executor.submit {
                Thread.sleep(Duration.ofSeconds(5))
                runItemEquipmentCycle()
            }
            return
        }

        snapshotFetchPhase.executeItemEquipment(executor, entries)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("[Scheduler] ITEM_EQUIPMENT cycle failed", ex)
                }
                running.set(false)
                executor.submit { runItemEquipmentCycle() }
            }
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
