package maple.cleanup.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cleanup")
data class CleanupProperties(
    val dryRun: Boolean = true,
    val runs: Runs = Runs(),
    val maxDeleteRunsPerCycle: Int = 10,
    /** 5 GB hard cap on bytes deleted per cleanup cycle. */
    val maxDeleteBytesPerCycle: Long = 5L * 1024 * 1024 * 1024,
    val maxRuntimeSeconds: Long = 60,
) {
    data class Runs(
        val keepRecent: Int = 5,
        val keepWithinHours: Long = 48,
    )
}
