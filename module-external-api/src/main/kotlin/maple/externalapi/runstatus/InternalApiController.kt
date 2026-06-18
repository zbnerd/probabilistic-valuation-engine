package maple.externalapi.runstatus

import java.util.UUID
import java.util.concurrent.ExecutorService
import maple.externalapi.scheduler.ExternalApiScheduler
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
) {
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
        val response = RunStatusResponse(
            slots = slots,
            lastCompletedByPhase = lastCompletedByPhase,
            current = runStatusTracker.getCurrentStatus(),
            lastCompleted = runStatusTracker.getLastCompletedRun(),
        )
        return ResponseEntity.ok(response)
    }

    @PostMapping("/trigger/daily")
    fun triggerDailyRefresh(
        @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
    ): ResponseEntity<Map<String, String>> {
        val existing = runStatusTracker.hasNonTerminalRun(PipelinePhase.RANKING_FETCH)
        if (existing != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING", "runId" to existing.runId))
        }

        val runId = airflowRunId ?: UUID.randomUUID().toString()
        executor.submit { scheduler.triggerDailyRefresh(runId).join() }
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

        val existing = runStatusTracker.hasNonTerminalRun(phase)
        if (existing != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING", "runId" to existing.runId))
        }

        val runId = airflowRunId ?: UUID.randomUUID().toString()
        executor.submit { scheduler.triggerPhase(phase, runId, upstreamRunId).join() }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED", "runId" to runId))
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
}
