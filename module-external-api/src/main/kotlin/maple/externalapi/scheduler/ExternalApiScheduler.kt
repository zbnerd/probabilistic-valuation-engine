package maple.externalapi.scheduler

import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.CharacterBasicFetchPhase
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import maple.externalapi.scheduler.phase.RunIdGenerator
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
    private val runIdGenerator: RunIdGenerator,
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

        // Generate the runId here, BEFORE the async chain starts, so the
        // run-status tracker transitions to RANKING_FETCH for the new run
        // immediately. Previously this happened inside the .handle callback
        // of ranking.execute(), which meant a new pipeline cycle would leave
        // the previous run's FAILED status visible on /api/internal/run-status
        // until ranking.fetch completed — and any failure before that
        // (e.g. ItemEquipmentContinuousLoop picking up a fresh OCID mapping
        // written by a sibling process) would never transition the tracker
        // at all. See bug repro in commit a4f380f1d.
        val runId = runIdGenerator.newRunId()
        runStatusTracker.startRun(runId)

        log.info("[Scheduler] starting ranking fetch phase: runId={}", runId)
        rankingPhase.execute(executor, runId)
            .handle { runKey, ex ->
                if (ex != null) {
                    log.error("[Scheduler] ranking fetch failed, cannot proceed with OCID lookup", ex)
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
                    runBlocking { ocidLookupPhase.execute(executor, runKey, runId) }
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
                    // Char-basic finished; item-equipment runs in a SEPARATE continuous loop
                    // (ItemEquipmentContinuousLoop) and signals run completion there. Marking
                    // CHARACTER_BASIC_DONE here so observers (Airflow sensor, /run-status API)
                    // can distinguish "char-basic finished, item-equipment still in flight"
                    // from "fully completed." ItemEquipmentContinuousLoop's whenComplete
                    // checks this phase and only then calls completeRun.
                    runStatusTracker.transitionPhase(PipelinePhase.CHARACTER_BASIC_DONE)
                    log.info("[Scheduler] char-basic finished, item-equipment in continuous loop")
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

    /**
     * Run RANKING_FETCH phase standalone. Acquires RANKING_FETCH slot, calls
     * ranking phase bean, completes slot on success (terminal record persists)
     * or fails+releases slot on exception. [upstreamRunId] is unused for
     * ranking (no upstream).
     */
    fun runRankingPhase(runId: String, upstreamRunId: String?): CompletableFuture<Void> {
        val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, runId)
        if (acquired == null) {
            return CompletableFuture.failedFuture(
                IllegalStateException("RANKING_FETCH slot occupied")
            )
        }

        val rankingPhase = rankingFetchPhaseProvider.ifAvailable
        if (rankingPhase == null) {
            runStatusTracker.failRun(PipelinePhase.RANKING_FETCH, runId, "ranking fetch phase not enabled")
            runStatusTracker.releasePhaseSlot(PipelinePhase.RANKING_FETCH, runId)
            return CompletableFuture.failedFuture(
                IllegalStateException("ranking fetch phase not enabled")
            )
        }

        val future = try {
            rankingPhase.execute(executor, runId)
        } catch (ex: Throwable) {
            log.error("[Scheduler] runRankingPhase sync failure runId={}", runId, ex)
            CompletableFuture.failedFuture<Void>(ex)
        }
        return future
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("[Scheduler] runRankingPhase failed runId={}", runId, ex)
                    runStatusTracker.failRun(PipelinePhase.RANKING_FETCH, runId, ex.message ?: "unknown")
                    runStatusTracker.releasePhaseSlot(PipelinePhase.RANKING_FETCH, runId)
                } else {
                    runStatusTracker.completeRun(PipelinePhase.RANKING_FETCH, runId, 0, 0)
                    // do NOT release — terminal record persists for /run-status
                }
            }
            .thenRun { }
    }

    /**
     * Run OCID_LOOKUP phase standalone. Reads character names from
     * [upstreamRunId]'s ranking chunks, fetches OCIDs, writes ocid-mapping
     * file. Acquires OCID_LOOKUP slot; completes on success (terminal
     * record persists), fails+releases on exception.
     */
    fun runOcidPhase(runId: String, upstreamRunId: String?): CompletableFuture<Void> {
        require(upstreamRunId != null) { "OCID_LOOKUP requires upstreamRunId" }
        val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, runId)
        if (acquired == null) {
            return CompletableFuture.failedFuture(
                IllegalStateException("OCID_LOOKUP slot occupied")
            )
        }

        val runKey = "runs/$upstreamRunId"
        val future = runCatching {
            runBlocking { ocidLookupPhase.execute(executor, runKey, runId) }
                .let { CompletableFuture.completedFuture(it) }
        }.getOrElse { ex ->
            log.error("[Scheduler] runOcidPhase sync failure runId={} upstreamRunId={}", runId, upstreamRunId, ex)
            CompletableFuture.failedFuture<Void>(ex)
        }
        return future
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("[Scheduler] runOcidPhase failed runId={} upstreamRunId={}", runId, upstreamRunId, ex)
                    runStatusTracker.failRun(PipelinePhase.OCID_LOOKUP, runId, ex.message ?: "unknown")
                    runStatusTracker.releasePhaseSlot(PipelinePhase.OCID_LOOKUP, runId)
                } else {
                    runStatusTracker.completeRun(PipelinePhase.OCID_LOOKUP, runId, 0, 0)
                }
            }
            .thenRun { }
    }

    override val lifecyclePhase: Int = 100

    override fun stopLifecycle() {
        log.info("[Scheduler] shutdown requested")
        shutdown.set(true)
        executor.close()
        itemEquipmentContinuousLoop.stop()
    }
}
