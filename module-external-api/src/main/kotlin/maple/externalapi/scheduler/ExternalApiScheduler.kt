package maple.externalapi.scheduler

import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import maple.externalapi.scheduler.phase.SnapshotFetchPhase
import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ExternalApiScheduler(
    private val ocidLookupPhase: OcidLookupPhase,
    private val snapshotFetchPhase: SnapshotFetchPhase,
    private val ocidCacheProvider: OcidCacheProvider,
    private val rankingFetchPhaseProvider: ObjectProvider<RankingFetchPhase>,
    @Value("\${external-api.schedule.run-on-startup:false}")
    private val runOnStartup: Boolean,
    @Value("\${external-api.schedule.skip-character-basic:false}")
    private val skipCharacterBasic: Boolean,
	) : ManagedLifecycle {
    private val log = LoggerFactory.getLogger(ExternalApiScheduler::class.java)
    private val running = AtomicBoolean(false)
    private val shutdown = AtomicBoolean(false)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()

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
        if (!acquireLock(3_600_000)) {
            log.warn("[Scheduler] could not acquire lock for daily refresh, skipping")
            return
        }
        if (skipCharacterBasic) {
            log.info("[Scheduler] skip-character-basic enabled, loading OCID cache from existing data")
            ocidCacheProvider.refresh()
            releaseLock()
            return
        }

        val rankingPhase = rankingFetchPhaseProvider.ifAvailable
        if (rankingPhase == null) {
            log.error("[Scheduler] ranking fetch phase is required but not enabled")
            releaseLock()
            return
        }

        log.info("[Scheduler] starting ranking fetch phase")
        rankingPhase.execute(executor)
            .handle { runDir, ex ->
                if (ex != null) {
                    log.error("[Scheduler] ranking fetch failed, cannot proceed with OCID lookup", ex)
                }
                runDir
            }
            .thenCompose { runDir ->
                if (runDir == null) {
                    CompletableFuture.completedFuture(null)
                } else {
                    ocidLookupPhase.execute(executor, runDir)
                }
            }
            .thenCompose {
                val cache = ocidCacheProvider.refresh()
                snapshotFetchPhase.executeCharacterBasic(executor, cache)
            }
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("[Scheduler] daily refresh failed", ex)
                }
                releaseLock()
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
                Thread.sleep(java.time.Duration.ofSeconds(30))
                ocidCacheProvider.refresh()
                runItemEquipmentCycle()
            }
            return
        }

        if (!acquireLock(120_000)) {
            executor.submit {
                Thread.sleep(java.time.Duration.ofSeconds(5))
                runItemEquipmentCycle()
            }
            return
        }

        CompletableFuture.completedFuture(null)
            .thenCompose { snapshotFetchPhase.executeItemEquipment(executor, entries) }
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("[Scheduler] ITEM_EQUIPMENT cycle failed", ex)
                }
                releaseLock()
                executor.submit { runItemEquipmentCycle() }
            }
    }

    private fun acquireLock(timeoutMs: Long): Boolean {
        lock.lock()
        try {
            var remainingNanos = timeoutMs * 1_000_000L
            while (!running.compareAndSet(false, true)) {
                if (remainingNanos <= 0) return false
                remainingNanos = idle.awaitNanos(remainingNanos)
            }
            return true
        } finally {
            lock.unlock()
        }
    }

    private fun releaseLock() {
        running.set(false)
        lock.lock()
        try { idle.signalAll() } finally { lock.unlock() }
    }

    override val lifecyclePhase: Int = 100

    override fun stopLifecycle() {
        log.info("[Scheduler] shutdown requested")
        shutdown.set(true)
        executor.close()
    }
}
