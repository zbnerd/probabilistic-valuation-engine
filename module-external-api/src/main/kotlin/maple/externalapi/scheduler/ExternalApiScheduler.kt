package maple.externalapi.scheduler

import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.metrics.SchedulerMetrics
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.CharacterBasicFetchPhase
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
import kotlinx.coroutines.runBlocking
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
    private val ocidCacheProvider: OcidCacheProvider,
    private val rankingFetchPhaseProvider: ObjectProvider<RankingFetchPhase>,
    private val characterBasicPhaseProvider: ObjectProvider<CharacterBasicFetchPhase>,
    private val itemEquipmentContinuousLoop: ItemEquipmentContinuousLoop,
    private val runStatusTracker: RunStatusTracker,
    private val schedulerMetrics: SchedulerMetrics,
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
            triggerDailyRefresh(null)
        }
        itemEquipmentContinuousLoop.startItemEquipmentLoopOnce()
    }

    @Scheduled(cron = "\${external-api.schedule.daily-cron:0 0 3 * * *}")
    fun scheduledDailyRefresh() {
        triggerDailyRefresh(null)
    }

    fun triggerDailyRefresh(airflowRunId: String?) {
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
            .handle { runKey, ex ->
                if (ex != null) {
                    log.error("[Scheduler] ranking fetch failed, cannot proceed with OCID lookup", ex)
                } else if (runKey != null) {
                    // runKey is "runs/<runId>"; extract runId for the status tracker.
                    val runId = runKey.removePrefix("runs/")
                    runStatusTracker.startRun(runId)
                }
                runKey
            }
            .thenCompose { runKey ->
                if (runKey == null) {
                    CompletableFuture.completedFuture(null)
                } else {
                    runStatusTracker.transitionPhase(PipelinePhase.OCID_LOOKUP)
                    // OcidLookupPhase.execute() is now suspend fun (Issue #1128).
                    // Caller thread is multi-threaded VT (Executors.newVirtualThreadPerTaskExecutor).
                    // runBlocking bridges to Default dispatcher for CPU offload.
                    // Single submit thread blocked for OCID lookup duration; no other submit affected.
                    runBlocking { ocidLookupPhase.execute(executor, runKey) }
                        .let { CompletableFuture.completedFuture(it) }
                }
            }
            .thenCompose {
                ocidCacheProvider.refresh()
                runStatusTracker.transitionPhase(PipelinePhase.CHARACTER_BASIC)
                val charBasicPhase = characterBasicPhaseProvider.ifAvailable
                if (charBasicPhase == null) {
                    log.warn("[Scheduler] character-basic phase not enabled, skipping")
                    CompletableFuture.completedFuture(null)
                } else {
                    val ocidCache = ocidCacheProvider.current()
                    if (ocidCache.isEmpty()) {
                        log.warn("[Scheduler] OCID cache empty after OCID lookup, skipping character-basic")
                        CompletableFuture.completedFuture(null)
                    } else {
                        log.info("[Scheduler] starting character-basic fetch ({} entries)", ocidCache.size)
                        charBasicPhase.execute(executor, ocidCache)
                    }
                }
            }
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("[Scheduler] daily refresh failed", ex)
                    runStatusTracker.getCurrentStatus()?.runId?.let { runId ->
                        runStatusTracker.failRun(runId, ex.message ?: "unknown")
                    }
                } else {
                    val chunks = schedulerMetrics.drainRunChunks().toInt()
                    val records = schedulerMetrics.drainRunRecords()
                    runStatusTracker.getCurrentStatus()?.runId?.let { runId ->
                        runStatusTracker.completeRun(runId, chunks, records)
                    }
                    log.info("[Scheduler] daily refresh completed, chunks={} records={}", chunks, records)
                }
                releaseLock()
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
        itemEquipmentContinuousLoop.stop()
    }
}
