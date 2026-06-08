package maple.externalapi.scheduler

import java.util.UUID
import java.util.concurrent.ExecutorService
import maple.expectation.error.exception.DistributedLockException
import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.metrics.SchedulerMetrics
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.CharacterBasicFetchPhase
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 1 hour — long enough for the full daily refresh pipeline (snapshot → ocid → ranking) to complete without contention, but bounded so a hung worker releases the lock within one refresh cycle. */
private const val DAILY_REFRESH_LOCK_TIMEOUT_MS: Long = 3_600_000L

@Component
class ExternalApiScheduler(
    private val ocidLookupPhase: OcidLookupPhase,
    private val characterBasicFetchPhase: CharacterBasicFetchPhase,
    private val ocidCacheProvider: OcidCacheProvider,
    private val rankingFetchPhaseProvider: ObjectProvider<RankingFetchPhase>,
    private val runStatusTracker: RunStatusTracker,
    private val schedulerMetrics: SchedulerMetrics,
    private val itemEquipmentLoop: ItemEquipmentContinuousLoop,
    @Value("\${external-api.schedule.enabled:false}")
    private val scheduleEnabled: Boolean,
    @Value("\${external-api.schedule.run-on-startup:false}")
    private val runOnStartup: Boolean,
    @Value("\${external-api.schedule.skip-character-basic:false}")
    private val skipCharacterBasic: Boolean,
    @Qualifier("externalApiSchedulerExecutor") private val executor: ExecutorService,
) : ManagedLifecycle {
    private val log = LoggerFactory.getLogger(ExternalApiScheduler::class.java)

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
        try {
            itemEquipmentLoop.acquireSchedulerLock("daily_refresh", DAILY_REFRESH_LOCK_TIMEOUT_MS)
        } catch (ex: DistributedLockException) {
            log.error("[Scheduler] could not acquire lock for daily refresh, skipping until next cron", ex)
            return
        }
        schedulerMetrics.incrementLockAcquired("daily_refresh")
        if (skipCharacterBasic) {
            log.info("[Scheduler] skip-character-basic enabled, loading OCID cache from existing data")
            ocidCacheProvider.refresh()
            itemEquipmentLoop.releaseSchedulerLock()
            itemEquipmentLoop.startItemEquipmentLoopOnce()
            return
        }

        val rankingPhase = rankingFetchPhaseProvider.ifAvailable
        if (rankingPhase == null) {
            log.error("[Scheduler] ranking fetch phase is required but not enabled")
            itemEquipmentLoop.releaseSchedulerLock()
            itemEquipmentLoop.startItemEquipmentLoopOnce()
            return
        }

        val runId = externalRunId ?: UUID.randomUUID().toString()
        runStatusTracker.startRun(runId)

        log.info("[Scheduler] starting ranking fetch phase, runId={}", runId)
        runStatusTracker.transitionPhase(PipelinePhase.RANKING_FETCH)
        rankingPhase.execute(executor)
            .thenCompose { runDir ->
                val resolved = runDir ?: error("ranking fetch returned null runDir")
                runStatusTracker.transitionPhase(PipelinePhase.OCID_LOOKUP)
                ocidLookupPhase.execute(executor, resolved)
            }
            .thenCompose { _ ->
                val cache = ocidCacheProvider.refresh()
                runStatusTracker.transitionPhase(PipelinePhase.CHARACTER_BASIC)
                characterBasicFetchPhase.execute(executor, cache)
            }
            .whenComplete { _, ex ->
                if (ex != null) {
                    val cause = ex.cause ?: ex
                    val message = cause.message ?: cause::class.simpleName ?: "unknown error"
                    runStatusTracker.failRun(runId, message)
                    log.error("[Scheduler] daily refresh failed, runId={}", runId, cause)
                    itemEquipmentLoop.releaseSchedulerLock()
                } else {
                    val chunks = schedulerMetrics.drainRunChunks().toInt()
                    val records = schedulerMetrics.drainRunRecords()
                    runStatusTracker.completeRun(runId, chunks, records)
                    log.info("[Scheduler] daily refresh completed, runId={} chunks={} records={}", runId, chunks, records)
                    itemEquipmentLoop.releaseSchedulerLock()
                    itemEquipmentLoop.startItemEquipmentLoopOnce()
                }
            }
    }

    override val lifecyclePhase: Int = 100

    override fun stopLifecycle() {
        log.info("[Scheduler] shutdown requested")
        itemEquipmentLoop.stop()
    }
}
