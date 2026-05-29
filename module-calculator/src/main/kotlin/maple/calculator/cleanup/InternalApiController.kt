package maple.calculator.cleanup

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@RestController
@RequestMapping("/api/internal")
class InternalApiController(
    @Autowired(required = false) private val resultCleanup: CalculatorResultCleanupScheduler?,
) {
    private val triggerExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val resultCleanupRunning = AtomicBoolean(false)

    @PostMapping("/trigger/result-cleanup")
    fun triggerResultCleanup(): ResponseEntity<Map<String, String>> {
        if (resultCleanup == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("status" to "DISABLED"))
        }
        if (!resultCleanupRunning.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING"))
        }
        triggerExecutor.submit {
            try {
                resultCleanup.cleanup()
            } finally {
                resultCleanupRunning.set(false)
            }
        }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED"))
    }

    @jakarta.annotation.PreDestroy
    fun shutdown() {
        triggerExecutor.close()
    }
}
