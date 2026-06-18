package maple.externalapi.runstatus

import java.util.UUID
import java.util.concurrent.ExecutorService
import maple.externalapi.loop.PhaseLoopController
import maple.externalapi.scheduler.ExternalApiScheduler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/internal")
class InternalApiController(
    private val runStatusTracker: RunStatusTracker,
    private val scheduler: ExternalApiScheduler,
    @Qualifier("internalApiExecutor") private val executor: ExecutorService,
    private val phaseLoopController: PhaseLoopController,
) {
    private val log = LoggerFactory.getLogger(InternalApiController::class.java)

    private val loopablePhases = setOf(
        PipelinePhase.ITEM_EQUIPMENT,
        PipelinePhase.CHARACTER_BASIC,
    )
    @GetMapping("/run-status")
    fun getRunStatus(): ResponseEntity<RunStatusResponse> {
        val phases = listOf(
            PipelinePhase.RANKING_FETCH,
            PipelinePhase.OCID_LOOKUP,
            PipelinePhase.CHARACTER_BASIC,
            PipelinePhase.ITEM_EQUIPMENT,
        )
        val slots = phases.associateWith { runStatusTracker.getPhaseStatus(it) }
        val lastCompletedByPhase = phases.associateWith {
            runStatusTracker.getLastCompletedForPhase(it)
        }
        val loopSummaries = phaseLoopController.activeLoops().associate { state ->
            state.phase.name to LoopSummaryView(
                loopId = state.loopId,
                phase = state.phase.name,
                startedAt = state.startedAt,
                iterationCount = state.iterationCount,
                lastRunId = state.lastRunId,
                status = state.status.name,
                lastError = state.lastError,
            )
        }
        val response = RunStatusResponse(
            slots = slots,
            lastCompletedByPhase = lastCompletedByPhase,
            current = runStatusTracker.getCurrentStatus(),
            lastCompleted = runStatusTracker.getLastCompletedRun(),
            loopSummaries = loopSummaries,
        )
        return ResponseEntity.ok(response)
    }

    @PostMapping("/trigger/daily")
    fun triggerDailyRefresh(
        @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
    ): ResponseEntity<Map<String, String>> {
        // Block daily trigger if any loop is active. Daily covers all 4 phases;
        // if any of them is being driven by a loop, the loop would be raced.
        for (phase in triggerablePhases) {
            if (phaseLoopController.hasActiveLoop(phase)) {
                val loopId = phaseLoopController.getLoopState(phase)?.loopId ?: ""
                return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf(
                    "status" to "LOOP_ACTIVE",
                    "phase" to phase.name,
                    "loopId" to loopId,
                ))
            }
        }
        val existing = runStatusTracker.hasNonTerminalRun(PipelinePhase.RANKING_FETCH)
        if (existing != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING", "runId" to existing.runId))
        }

        val runId = airflowRunId ?: UUID.randomUUID().toString()
        executor.submit { scheduler.triggerDailyRefresh(runId) }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED", "runId" to runId))
    }

    private val triggerablePhases = setOf(
        PipelinePhase.RANKING_FETCH,
        PipelinePhase.OCID_LOOKUP,
        PipelinePhase.CHARACTER_BASIC,
        PipelinePhase.ITEM_EQUIPMENT,
    )

    @PostMapping("/trigger/phase/{phaseName}")
    fun triggerPhase(
        @PathVariable phaseName: String,
        @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
        @RequestHeader("X-Upstream-Run-Id", required = false) upstreamRunId: String?,
    ): ResponseEntity<Map<String, String>> {
        val phase = runCatching { PipelinePhase.valueOf(phaseName) }.getOrNull()
        if (phase == null || phase !in triggerablePhases) {
            return badRequestInvalidPhase()
        }
        if (phase != PipelinePhase.RANKING_FETCH && upstreamRunId.isNullOrBlank()) {
            return badRequestMissingUpstream(phase)
        }
        if (phaseLoopController.hasActiveLoop(phase)) {
            val loopId = phaseLoopController.getLoopState(phase)?.loopId ?: ""
            return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf(
                "status" to "LOOP_ACTIVE",
                "phase" to phase.name,
                "loopId" to loopId,
            ))
        }

        val existing = runStatusTracker.hasNonTerminalRun(phase)
        if (existing != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING", "runId" to existing.runId))
        }

        val runId = airflowRunId ?: UUID.randomUUID().toString()
        executor.submit { scheduler.triggerPhase(phase, runId, upstreamRunId) }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED", "runId" to runId))
    }

    @PostMapping("/stop/phase/{phaseName}")
    fun stopPhase(
        @PathVariable phaseName: String,
        @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
    ): ResponseEntity<Map<String, String>> {
        val phase = runCatching { PipelinePhase.valueOf(phaseName) }.getOrNull()
        if (phase == null || phase !in triggerablePhases) {
            return badRequestInvalidPhase()
        }
        // Per spec §5.3: this endpoint trips the same PhaseStopSignal as
        // /stop/loop/phase/{name}. If a loop is active for `phase`, the
        // current loop iteration halts at the next chunk boundary and the
        // loop finalizes. Log a warning so operators see the side-effect.
        if (phaseLoopController.hasActiveLoop(phase)) {
            val loopId = phaseLoopController.getLoopState(phase)?.loopId ?: ""
            log.warn(
                "[InternalApi] /stop/phase tripped loop for active loop — phase={} loopId={} airflowRunId={}; " +
                    "use /stop/loop/phase/{} to target the loop explicitly",
                phase, loopId, airflowRunId, phase,
            )
        }
        val wasRunning = scheduler.requestPhaseStop(phase)
        if (wasRunning) {
            val runId = runStatusTracker.getPhaseStatus(phase)?.runId ?: ""
            log.info(
                "[InternalApi] stop requested phase={} runId={} airflowRunId={}",
                phase, runId, airflowRunId,
            )
            return ResponseEntity.accepted().body(mapOf(
                "status" to "STOP_REQUESTED",
                "phase" to phase.name,
                "runId" to runId,
                "airflowRunId" to (airflowRunId ?: ""),
            ))
        }
        val lastRunId = runStatusTracker.getLastCompletedForPhase(phase)?.runId ?: ""
        return ResponseEntity.ok().body(mapOf(
            "status" to "NOT_RUNNING",
            "phase" to phase.name,
            "runId" to lastRunId,
            "airflowRunId" to (airflowRunId ?: ""),
        ))
    }

    private fun badRequestInvalidPhase(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf(
                "error" to "INVALID_PHASE",
                "allowed" to triggerablePhases.joinToString(",") { it.name },
            ))

    private fun badRequestMissingUpstream(phase: PipelinePhase): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to "MISSING_UPSTREAM", "phase" to phase.name))

    @PostMapping("/loop/phase/{phaseName}")
    fun startLoop(
        @PathVariable phaseName: String,
        @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
    ): ResponseEntity<Map<String, String>> {
        val phase = runCatching { PipelinePhase.valueOf(phaseName) }.getOrNull()
        if (phase == null || phase !in loopablePhases) {
            return badRequestInvalidLoopablePhase()
        }
        val state = phaseLoopController.startLoop(phase)
        log.info(
            "[InternalApi] loop start phase={} loopId={} airflowRunId={}",
            phase, state.loopId, airflowRunId,
        )
        return ResponseEntity.accepted().body(mapOf(
            "status" to "LOOP_STARTED",
            "phase" to phase.name,
            "loopId" to state.loopId,
            "iterationCount" to state.iterationCount.toString(),
            "airflowRunId" to (airflowRunId ?: ""),
        ))
    }

    @PostMapping("/stop/loop/phase/{phaseName}")
    fun stopLoop(
        @PathVariable phaseName: String,
        @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
    ): ResponseEntity<Map<String, String>> {
        val phase = runCatching { PipelinePhase.valueOf(phaseName) }.getOrNull()
        if (phase == null || phase !in loopablePhases) {
            return badRequestInvalidLoopablePhase()
        }
        val state = phaseLoopController.stopLoop(phase)
        if (state != null) {
            log.info(
                "[InternalApi] loop stop requested phase={} loopId={} iterations={} airflowRunId={}",
                phase, state.loopId, state.iterationCount, airflowRunId,
            )
            return ResponseEntity.accepted().body(mapOf(
                "status" to "STOP_REQUESTED",
                "phase" to phase.name,
                "loopId" to state.loopId,
                "iterationCount" to state.iterationCount.toString(),
                "airflowRunId" to (airflowRunId ?: ""),
            ))
        }
        return ResponseEntity.ok().body(mapOf(
            "status" to "NOT_LOOPING",
            "phase" to phase.name,
            "airflowRunId" to (airflowRunId ?: ""),
        ))
    }

    private fun badRequestInvalidLoopablePhase(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf(
                "error" to "INVALID_PHASE",
                "allowed" to loopablePhases.joinToString(",") { it.name },
            ))
}
