package maple.externalapi.scheduler

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import maple.expectation.error.exception.DistributedLockException
import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.metrics.SchedulerMetrics
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.ItemEquipmentFetchPhase
import maple.externalapi.scheduler.phase.RunIdGenerator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Owns the continuous ITEM_EQUIPMENT fetch loop and the shared scheduler lock.
 * Separated from [ExternalApiScheduler] so the scheduler can focus on the daily
 * pipeline trigger + lifecycle, and this class focuses on the long-running cycle.
 *
 * Run-completion signal: ExternalApiScheduler transitions the run to
 * [PipelinePhase.CHARACTER_BASIC_DONE] when char-basic ends. The first item-equipment
 * cycle that completes AFTER that phase transition signals full run completion
 * (sink closed, manifest + _SUCCESS marker written) via RunStatusTracker.completeRun.
 * The phase guard prevents subsequent cycles from re-completing the same run.
 *
 * State: one `ReentrantLock` + condition for the distributed mutex, and three
 * `AtomicBoolean`s for "loop started", "loop running", and "shutdown requested".
 */
@Component
class ItemEquipmentContinuousLoop(
    private val itemEquipmentFetchPhase: ItemEquipmentFetchPhase,
    private val ocidCacheProvider: OcidCacheProvider,
    private val schedulerMetrics: SchedulerMetrics,
    private val runStatusTracker: RunStatusTracker,
    private val runIdGenerator: RunIdGenerator,
    @Qualifier("externalApiSchedulerExecutor") private val executor: ExecutorService,
) {
    private val log = LoggerFactory.getLogger(ItemEquipmentContinuousLoop::class.java)
    private val running = AtomicBoolean(false)
    private val shutdown = AtomicBoolean(false)
    internal val itemEquipmentStarted = AtomicBoolean(false)
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()

    fun startItemEquipmentLoopOnce() {
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

        try {
            acquireLock("item_equipment", 120_000)
        } catch (ex: DistributedLockException) {
            log.error("[Scheduler] could not acquire lock for ITEM_EQUIPMENT, scheduling single retry in 60s", ex)
            executor.submit {
                Thread.sleep(java.time.Duration.ofSeconds(60))
                runItemEquipmentCycle()
            }
            return
        }
        schedulerMetrics.incrementLockAcquired("item_equipment")

        // Generate a per-cycle runId. The run-status tracker is updated
        // ONLY if no daily pipeline is currently in flight — otherwise we
        // would overwrite the daily run's runId and break the Airflow
        // sensor (which expects the daily runId throughout the poll).
        // The previous bug: PR #1278 unconditionally called
        // startItemEquipmentCycle on every cycle, which clobbered the
        // daily trigger's runId mid-pipeline and caused the Airflow
        // sensor to mark the DAG FAILED after 2h of "mismatch" retries.
        val cycleRunId = runIdGenerator.newRunId()
        val current = runStatusTracker.getCurrentStatus()
        if (current == null || current.isTerminal) {
            // Either no run is tracked yet, or the last tracked run
            // (daily or earlier cycle) has reached a terminal state.
            // Safe to register this cycle's runId in /run-status.
            // Initial phase is ITEM_EQUIPMENT, not RANKING_FETCH — the
            // full ranking→ocid→char-basic chain (if any) is long over.
            runStatusTracker.startItemEquipmentCycle(cycleRunId)
        }
        // else: a daily pipeline is in flight (RANKING_FETCH →
        // CHARACTER_BASIC_DONE). The loop's per-cycle runId stays a
        // log-correlation handle only; /run-status continues to show the
        // daily runId. When the daily completes via the completeRun
        // guard below, the next loop cycle will start a fresh
        // ITEM_EQUIPMENT-status run.

        CompletableFuture.completedFuture(null)
            .thenCompose { itemEquipmentFetchPhase.execute(executor, entries, cycleRunId) }
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("[Scheduler] ITEM_EQUIPMENT cycle failed", ex)
                } else {
                    // Cycle finished cleanly (sink closed, manifest + _SUCCESS written).
                    // If char-basic has already finished for the current run, this cycle
                    // represents run completion. Guarded by phase to make it idempotent —
                    // subsequent cycles see COMPLETED and skip the completeRun call.
                    val current = runStatusTracker.getCurrentStatus()
                    if (current != null &&
                        current.phase == PipelinePhase.CHARACTER_BASIC_DONE
                    ) {
                        val chunks = schedulerMetrics.drainRunChunks().toInt()
                        val records = schedulerMetrics.drainRunRecords()
                        runStatusTracker.completeRun(current.runId, chunks, records)
                        log.info(
                            "[Scheduler] run completed, runId={} chunks={} records={}",
                            current.runId, chunks, records,
                        )
                    }
                }
                releaseLock()
                executor.submit { runItemEquipmentCycle() }
            }
    }

    private fun acquireLock(phase: String, timeoutMs: Long) {
        lock.lock()
        try {
            var remainingNanos = timeoutMs * 1_000_000L
            while (!running.compareAndSet(false, true)) {
                if (remainingNanos <= 0) {
                    schedulerMetrics.incrementLockTimeout(phase)
                    throw DistributedLockException("ItemEquipmentContinuousLoop:$phase")
                }
                remainingNanos = idle.awaitNanos(remainingNanos)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun releaseLock() {
        running.set(false)
        lock.lock()
        try {
            idle.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** Acquire the shared scheduler lock. Used by [ExternalApiScheduler] for the daily refresh. */
    fun acquireSchedulerLock(name: String, timeoutMs: Long) {
        acquireLock(name, timeoutMs)
    }

    /** Release the shared scheduler lock. Used by [ExternalApiScheduler] for the daily refresh. */
    fun releaseSchedulerLock() {
        releaseLock()
    }

    /** Request loop termination. Idempotent. */
    fun stop() {
        shutdown.set(true)
    }
}
