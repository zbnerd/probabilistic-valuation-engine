package maple.calculator.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("calculator.cleanup")
data class CalculatorCleanupProperties(
    val dryRun: Boolean = true,
    val runs: Runs = Runs(),
    val maxDeleteRunsPerCycle: Int = 10,
    /** 5 GB hard cap on bytes deleted per cleanup cycle to avoid long DB transactions. */
    val maxDeleteBytesPerCycle: Long = 5L * 1024 * 1024 * 1024,
    val maxRuntimeSeconds: Long = 60,
) {
    data class Runs(
        val keepRecent: Int = 5,
        val keepWithinHours: Long = 48,
    )
}
