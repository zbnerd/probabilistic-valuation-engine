package maple.externalapi.scheduler

import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.metrics.SchedulerMetrics
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.CharacterBasicFetchPhase
import maple.externalapi.scheduler.phase.ItemEquipmentFetchPhase
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

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ExternalApiScheduler(
    private val ocidLookupPhase: OcidLookupPhase,
    private val ocidCacheProvider: OcidCacheProvider,
    private val rankingFetchPhaseProvider: ObjectProvider<RankingFetchPhase>,
    private val characterBasicPhaseProvider: ObjectProvider<CharacterBasicFetchPhase>,
    private val itemEquipmentFetchPhaseProvider: ObjectProvider<ItemEquipmentFetchPhase>,
    private val schedulerMetrics: SchedulerMetrics,
    private val runStatusTracker: RunStatusTracker,
    private val runIdGenerator: RunIdGenerator,
    @Value("\${external-api.schedule.run-on-startup:false}")
    private val runOnStartup: Boolean,
    @Value("\${external-api.schedule.skip-character-basic:false}")
    private val skipCharacterBasic: Boolean,
    private val stopSignal: PhaseStopSignal,
	) : ManagedLifecycle {
    private val log = LoggerFactory.getLogger(ExternalApiScheduler::class.java)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        ocidCacheProvider.refresh()
        if (runOnStartup) {
            log.info("[Scheduler] run-on-startup enabled, triggering daily refresh")
            triggerDailyRefresh(null)
        }
    }

    @Scheduled(cron = "\${external-api.schedule.daily-cron:0 0 3 * * *}")
    fun scheduledDailyRefresh() {
        triggerDailyRefresh(null)
    }

    /**
     * Daily pipeline trigger. Chains 4 per-phase runs sequentially, each with
     * its own runId. The 4 phase slots get 4 distinct runIds during the run;
     * per-phase triggers POST sees each slot occupied by the daily's corresponding
     * sub-runId.
     *
     * The 409 protection lives in the controller layer (it checks RANKING_FETCH
     * slot occupancy before submitting). Per-phase slot acquisition in
     * `acquirePhaseSlot` prevents double-runs.
     *
     * @param airflowRunId the runId passed by Airflow (or null for cron / manual
     *   triggers). Used as log correlation only; the actual phase runIds are
     *   generated internally and returned to the controller as 202 STARTED.
     */
    fun triggerDailyRefresh(airflowRunId: String?): CompletableFuture<Void> {
        if (skipCharacterBasic) {
            log.info("[Scheduler] skip-character-basic enabled, loading OCID cache from existing data")
            return runCatching { ocidCacheProvider.refresh() }
                .fold(
                    onSuccess = { CompletableFuture.completedFuture(null) },
                    onFailure = { ex ->
                        log.error("[Scheduler] skip-character-basic refresh failed", ex)
                        CompletableFuture.failedFuture<Void>(ex)
                    },
                )
        }

        val rankingPhase = rankingFetchPhaseProvider.ifAvailable
        if (rankingPhase == null) {
            log.error("[Scheduler] ranking fetch phase is required but not enabled")
            return CompletableFuture.failedFuture(
                IllegalStateException("ranking fetch phase not enabled")
            )
        }

        val rRunId = runIdGenerator.newRunId()
        val oRunId = runIdGenerator.newRunId()
        val cbRunId = runIdGenerator.newRunId()
        val ieRunId = runIdGenerator.newRunId()

        log.info("[Scheduler] daily chain starting airflowRunId={} r={} o={} cb={} ie={}",
            airflowRunId, rRunId, oRunId, cbRunId, ieRunId)

        return triggerPhase(PipelinePhase.RANKING_FETCH, rRunId, null)
            .thenCompose {
                triggerPhase(PipelinePhase.OCID_LOOKUP, oRunId, rRunId)
            }
            .thenCompose {
                if (characterBasicPhaseProvider.ifAvailable == null) {
                    log.warn("[Scheduler] character-basic phase not enabled, skipping")
                    CompletableFuture.completedFuture(null)
                } else {
                    ocidCacheProvider.refresh()
                    triggerPhase(PipelinePhase.CHARACTER_BASIC, cbRunId, oRunId)
                }
            }
            .thenCompose {
                triggerPhase(PipelinePhase.ITEM_EQUIPMENT, ieRunId, cbRunId)
            }
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("[Scheduler] daily chain failed airflowRunId={} r={} o={} cb={} ie={}",
                        airflowRunId, rRunId, oRunId, cbRunId, ieRunId, ex)
                } else {
                    log.info("[Scheduler] daily chain completed airflowRunId={} r={} o={} cb={} ie={}",
                        airflowRunId, rRunId, oRunId, cbRunId, ieRunId)
                }
            }
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
                handlePhaseTerminal(
                    phase = PipelinePhase.RANKING_FETCH,
                    phaseLabel = "runRankingPhase",
                    runId = runId,
                    ex = ex,
                )
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
                handlePhaseTerminal(
                    phase = PipelinePhase.OCID_LOOKUP,
                    phaseLabel = "runOcidPhase",
                    runId = runId,
                    ex = ex,
                    failureLogContext = { "upstreamRunId={$upstreamRunId}" },
                )
            }
            .thenRun { }
    }

    /**
     * Run CHARACTER_BASIC phase standalone. Loads OCID cache from
     * [upstreamRunId]'s mapping file (Revision 3), calls char-basic phase
     * bean with the loaded cache. [upstreamRunId] is required; if the
     * loaded cache is empty, the phase short-circuits (consistent with
     * daily-refresh behavior).
     */
    fun runCharBasicPhase(runId: String, upstreamRunId: String?): CompletableFuture<Void> {
        require(upstreamRunId != null) { "CHARACTER_BASIC requires upstreamRunId" }
        val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, runId)
        if (acquired == null) {
            return CompletableFuture.failedFuture(
                IllegalStateException("CHARACTER_BASIC slot occupied")
            )
        }

        val charBasicPhase = characterBasicPhaseProvider.ifAvailable
        if (charBasicPhase == null) {
            runStatusTracker.failRun(PipelinePhase.CHARACTER_BASIC, runId, "character-basic phase not enabled")
            runStatusTracker.releasePhaseSlot(PipelinePhase.CHARACTER_BASIC, runId)
            return CompletableFuture.failedFuture(
                IllegalStateException("character-basic phase not enabled")
            )
        }

        val ocidCache = ocidCacheProvider.loadFromRun(upstreamRunId)
        if (ocidCache.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty for upstreamRunId={}, skipping character-basic runId={}", upstreamRunId, runId)
            runStatusTracker.completeRun(PipelinePhase.CHARACTER_BASIC, runId, 0, 0)
            return CompletableFuture.completedFuture(null)
        }

        val future = try {
            charBasicPhase.execute(executor, ocidCache, runId)
        } catch (ex: Throwable) {
            log.error("[Scheduler] runCharBasicPhase sync failure runId={}", runId, ex)
            CompletableFuture.failedFuture<Void>(ex)
        }
        return future
            .whenComplete { _, ex ->
                handlePhaseTerminal(
                    phase = PipelinePhase.CHARACTER_BASIC,
                    phaseLabel = "runCharBasicPhase",
                    runId = runId,
                    ex = ex,
                )
            }
            .thenRun { }
    }

    /**
     * Run ITEM_EQUIPMENT phase standalone. Loads OCID cache from
     * [upstreamRunId], calls itemEquipmentFetchPhase bean, drains
     * SchedulerMetrics for chunks/records, completes slot.
     *
     * Single-shot: does not loop. Caller (controller or triggerPhase)
     * decides how often to invoke.
     */
    fun runItemEquipmentPhase(runId: String, upstreamRunId: String?): CompletableFuture<Void> {
        require(upstreamRunId != null) { "ITEM_EQUIPMENT requires upstreamRunId" }
        val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, runId)
        if (acquired == null) {
            return CompletableFuture.failedFuture(
                IllegalStateException("ITEM_EQUIPMENT slot occupied")
            )
        }

        val itemEquipmentPhase = itemEquipmentFetchPhaseProvider.ifAvailable
        if (itemEquipmentPhase == null) {
            runStatusTracker.failRun(PipelinePhase.ITEM_EQUIPMENT, runId, "item-equipment phase not enabled")
            runStatusTracker.releasePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, runId)
            return CompletableFuture.failedFuture(
                IllegalStateException("item-equipment phase not enabled")
            )
        }

        val ocidCache = ocidCacheProvider.loadFromRun(upstreamRunId)
        val entries = ocidCache.entries.toList()
        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty for upstreamRunId={}, skipping item-equipment runId={}", upstreamRunId, runId)
            runStatusTracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, runId, 0, 0)
            return CompletableFuture.completedFuture(null)
        }

        val future = try {
            itemEquipmentPhase.execute(executor, entries, runId)
        } catch (ex: Throwable) {
            log.error("[Scheduler] runItemEquipmentPhase sync failure runId={}", runId, ex)
            CompletableFuture.failedFuture<Void>(ex)
        }
        return future
            .whenComplete { _, ex ->
                handlePhaseTerminal(
                    phase = PipelinePhase.ITEM_EQUIPMENT,
                    phaseLabel = "runItemEquipmentPhase",
                    runId = runId,
                    ex = ex,
                    drainMetrics = { schedulerMetrics.drainRunChunks().toInt() to schedulerMetrics.drainRunRecords() },
                )
            }
            .thenRun { }
    }

    /**
     * Set the stop flag for [phase] if and only if its slot currently holds a
     * non-terminal run. Returns true if a stop was applied (either just now or
     * already applied — flag is idempotent); false if there is nothing to stop.
     *
     * Cross-phase safe: only the named phase's flag is set. Other phases
     * continue uninterrupted.
     */
    fun requestPhaseStop(phase: PipelinePhase): Boolean {
        val hadNonTerminal = runStatusTracker.hasNonTerminalRun(phase) != null
        if (hadNonTerminal) {
            stopSignal.requestStop(phase)
            log.info("[Scheduler] stop requested phase={} runId={}",
                phase, runStatusTracker.getPhaseStatus(phase)?.runId)
        }
        return hadNonTerminal || stopSignal.isStopRequested(phase)
    }

    /**
     * Public entry point. Dispatches to the right per-phase method based on [phase].
     * Returns a CompletableFuture that completes when the phase reaches terminal
     * state (COMPLETED or FAILED). The /api/internal/trigger/phase controller
     * and triggerDailyRefresh both call this.
     *
     * Phases IDLE, OCID_CACHE_REFRESH, CHARACTER_BASIC_DONE, COMPLETED, FAILED
     * are not valid standalone triggers — they are intermediate states. Returns
     * a failed future for these.
     */
    fun triggerPhase(phase: PipelinePhase, runId: String, upstreamRunId: String?): CompletableFuture<Void> {
        return when (phase) {
            PipelinePhase.RANKING_FETCH -> runRankingPhase(runId, upstreamRunId)
            PipelinePhase.OCID_LOOKUP -> runOcidPhase(runId, upstreamRunId)
            PipelinePhase.CHARACTER_BASIC -> runCharBasicPhase(runId, upstreamRunId)
            PipelinePhase.ITEM_EQUIPMENT -> runItemEquipmentPhase(runId, upstreamRunId)
            else -> CompletableFuture.failedFuture(
                IllegalArgumentException("Phase $phase is not a standalone-triggerable phase")
            )
        }
    }

    /**
     * Terminal-state handler shared by all per-phase `runXxxPhase` methods.
     *
     * Behavior is identical to the original inline `whenComplete` blocks:
     * - [PhaseStoppedException]: INFO log, [RunStatusTracker.stopRun], clear stop signal
     * - other [Throwable]: ERROR log, [RunStatusTracker.failRun], release slot, clear stop signal
     * - success: [RunStatusTracker.completeRun], clear stop signal (terminal record persists)
     *
     * [drainMetrics] is only called on the stop and success branches (matching
     * the original behavior — failure branch intentionally does not drain).
     * [failureLogContext] is appended to the ERROR log line for phases that
     * carry extra context (e.g. OCID_LOOKUP's upstreamRunId).
     */
    private fun handlePhaseTerminal(
        phase: PipelinePhase,
        phaseLabel: String,
        runId: String,
        ex: Throwable?,
        drainMetrics: () -> Pair<Int, Long> = { 0 to 0L },
        failureLogContext: () -> String? = { null },
    ) {
        when {
            ex is PhaseStoppedException -> {
                log.info("[Scheduler] {} stopped runId={} phase={}", phaseLabel, runId, ex.phase)
                val (chunks, records) = drainMetrics()
                runStatusTracker.stopRun(phase, runId, chunks, records)
                stopSignal.clear(phase)
            }
            ex != null -> {
                val extra = failureLogContext()?.let { " $it" } ?: ""
                log.error("[Scheduler] {} failed runId={}$extra", phaseLabel, runId, ex)
                runStatusTracker.failRun(phase, runId, ex.message ?: "unknown")
                runStatusTracker.releasePhaseSlot(phase, runId)
                stopSignal.clear(phase)
            }
            else -> {
                val (chunks, records) = drainMetrics()
                runStatusTracker.completeRun(phase, runId, chunks, records)
                stopSignal.clear(phase)
            }
        }
    }

    override val lifecyclePhase: Int = 100

    override fun stopLifecycle() {
        log.info("[Scheduler] shutdown requested")
        executor.close()
    }
}
