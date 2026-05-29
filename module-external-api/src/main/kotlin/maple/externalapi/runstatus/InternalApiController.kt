package maple.externalapi.runstatus

import maple.externalapi.scheduler.ExternalApiScheduler
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.Executors

@RestController
@RequestMapping("/api/internal")
class InternalApiController(
    private val runStatusTracker: RunStatusTracker,
    private val scheduler: ExternalApiScheduler,
) {
    private val triggerExecutor = Executors.newVirtualThreadPerTaskExecutor()

    @GetMapping("/run-status")
    fun getRunStatus(): ResponseEntity<RunStatusResponse> {
        val response = RunStatusResponse(
            current = runStatusTracker.getCurrentStatus(),
            lastCompleted = runStatusTracker.getLastCompletedRun(),
        )
        return ResponseEntity.ok(response)
    }

    @PostMapping("/trigger/daily")
    fun triggerDailyRefresh(
        @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
    ): ResponseEntity<Map<String, String>> {
        val current = runStatusTracker.getCurrentStatus()
        if (current != null && !current.isTerminal) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING", "runId" to current.runId))
        }

        val runId = airflowRunId ?: UUID.randomUUID().toString()
        triggerExecutor.submit { scheduler.triggerDailyRefresh(runId) }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED", "runId" to runId))
    }
}

data class RunStatusResponse(
    val current: RunStatus?,
    val lastCompleted: RunStatus?,
)
