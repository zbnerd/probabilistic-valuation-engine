package maple.externalapi.scheduler

import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import maple.externalapi.scheduler.phase.SnapshotFetchPhase
import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Qualifier
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

@Component
class ExternalApiScheduler(
    private val ocidLookupPhase: OcidLookupPhase,
    private val snapshotFetchPhase: SnapshotFetchPhase,
    private val ocidCacheProvider: OcidCacheProvider,
    private val rankingFetchPhaseProvider: ObjectProvider<RankingFetchPhase>,
    private val runStatusTracker: RunStatusTracker,
    @Value("\${external-api.schedule.enabled:false}")
    private val scheduleEnabled: Boolean,
    @Value("\${external-api.schedule.run-on-startup:false}")
    private val runOnStartup: Boolean,
    @Value("\${external-api.schedule.skip-character-basic:false}")
    private val skipCharacterBasic: Boolean,
    @Qualifier("externalApiSchedulerExecutor") private val executor: ExecutorService,
) : ManagedLifecycle {
    private val log = LoggerFactory.getLogger(ExternalApiScheduler::class.java)
    private val running = AtomicBoolean(false)
    private val shutdown = AtomicBoolean(false)
    private val itemEquipmentStarted = AtomicBoolean(false)
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        if (!scheduleEnabled) return
        ocidCacheProvider.refresh()
        if (runOnStartup) {
            log.info("[Scheduler] run-on-startup enabled, triggering daily refresh")
            triggerDailyRefresh()
        }
    }

    @Scheduled(cron = "\${external-api.schedule.daily-cron:0 0 3 * * *}")
    fun scheduledDailyRefresh() {
        if (!scheduleEnabled) return
        triggerDailyRefresh()
    }

    fun triggerDailyRefresh(externalRunId: String? = null) {
        if (!acquireLock(3_600_000)) {
            log.warn("[Scheduler] could not acquire lock for daily refresh, skipping")
            return
        }
        if (skipCharacterBasic) {
            log.info("[Scheduler] skip-character-basic enabled, loading OCID cache from existing data")
            ocidCacheProvider.refresh()
            releaseLock()
            startItemEquipmentLoopOnce()
            return
        }

        val rankingPhase = rankingFetchPhaseProvider.ifAvailable
        if (rankingPhase == null) {
            log.error("[Scheduler] ranking fetch phase is required but not enabled")
            releaseLock()
            startItemEquipmentLoopOnce()
            return
        }

        val runId = externalRunId ?: UUID.randomUUID().toString()
        runStatusTracker.startRun(runId)

        log.info("[Scheduler] starting ranking fetch phase, runId={}", runId)
        runStatusTracker.transitionPhase(PipelinePhase.RANKING_FETCH)
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
                    runStatusTracker.transitionPhase(PipelinePhase.OCID_LOOKUP)
                    ocidLookupPhase.execute(executor, runDir)
                }
            }
            .thenCompose {
                val cache = ocidCacheProvider.refresh()
                runStatusTracker.transitionPhase(PipelinePhase.CHARACTER_BASIC)
                snapshotFetchPhase.executeCharacterBasic(executor, cache)
            }
            .whenComplete { _, ex ->
                if (ex != null) {
                    val message = ex.cause?.message ?: ex.message ?: "unknown error"
                    runStatusTracker.failRun(runId, message)
                    log.error("[Scheduler] daily refresh failed, runId={}", runId, ex)
                } else {
                    runStatusTracker.completeRun(runId, 0, 0)
                    log.info("[Scheduler] daily refresh completed, runId={}", runId)
                }
                releaseLock()
                startItemEquipmentLoopOnce()
            }
    }

    private fun startItemEquipmentLoopOnce() {
        if (itemEquipmentStarted.compareAndSet(false, true)) {
            executor.submit { runItemEquipmentLoop() }
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
    }
}
