package maple.externalapi.runstatus

import maple.externalapi.cleanup.ArtifactCleanupScheduler
import maple.externalapi.cleanup.ConsumedChunkCleanupScheduler
import maple.externalapi.scheduler.ExternalApiScheduler
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

@RestController
@RequestMapping("/api/internal")
class InternalApiController(
    private val runStatusTracker: RunStatusTracker,
    private val scheduler: ExternalApiScheduler,
    @Autowired(required = false) private val artifactCleanup: ArtifactCleanupScheduler?,
    @Autowired(required = false) private val consumedCleanup: ConsumedChunkCleanupScheduler?,
    @Qualifier("internalApiExecutor") private val executor: ExecutorService,
) {
    private val artifactCleanupRunning = AtomicBoolean(false)
    private val consumedCleanupRunning = AtomicBoolean(false)

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
        executor.submit { scheduler.triggerDailyRefresh(runId) }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED", "runId" to runId))
    }

    @PostMapping("/trigger/artifact-cleanup")
    fun triggerArtifactCleanup(): ResponseEntity<Map<String, String>> {
        if (artifactCleanup == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("status" to "DISABLED"))
        }
        if (!artifactCleanupRunning.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING"))
        }
        executor.submit {
            try { artifactCleanup.cleanup() } finally { artifactCleanupRunning.set(false) }
        }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED"))
    }

    @PostMapping("/trigger/consumed-cleanup")
    fun triggerConsumedCleanup(): ResponseEntity<Map<String, String>> {
        if (consumedCleanup == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("status" to "DISABLED"))
        }
        if (!consumedCleanupRunning.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING"))
        }
        executor.submit {
            try { consumedCleanup.cleanup() } finally { consumedCleanupRunning.set(false) }
        }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED"))
    }

}

data class RunStatusResponse(
    val current: RunStatus?,
    val lastCompleted: RunStatus?,
)
