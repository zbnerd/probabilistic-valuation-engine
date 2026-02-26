package maple.expectation.infrastructure.shutdown

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties(prefix = "shutdown")
class ShutdownProperties {
    // ========== Coordinator 설정 (P1-2) ==========

    @NotNull
    var equipmentAwaitTimeout: Duration = Duration.ofSeconds(20)

    @Min(1)
    @Max(30)
    var lockWaitSeconds: Int = 3

    @Min(5)
    @Max(60)
    var lockLeaseSeconds: Int = 10

    // ========== Batch Shutdown Handler 설정 (P1-3) ==========

    @Min(50)
    @Max(1000)
    var batchSize: Int = 200

    @Min(1)
    @Max(10)
    var emptyBatchRetryCount: Int = 3

    @Min(50)
    @Max(1000)
    var emptyBatchWaitMs: Long = 100

    // ========== Persistence 설정 (P1-1, P1-5) ==========

    @NotBlank
    var backupDirectory: String = "/tmp/maple-shutdown"

    @NotBlank
    var archiveDirectory: String = "/tmp/maple-shutdown/processed"

    @NotBlank
    var instanceId: String = "default-instance"
}
