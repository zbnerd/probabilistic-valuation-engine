package maple.calculator.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("calculator.cleanup")
data class CalculatorCleanupProperties(
    val dryRun: Boolean = true,
    val runs: Runs = Runs(),
    val maxDeleteRunsPerCycle: Int = 10,
    val maxDeleteBytesPerCycle: Long = 5368709120,
    val maxRuntimeSeconds: Long = 60,
) {
    data class Runs(
        val keepRecent: Int = 5,
        val keepWithinHours: Long = 48,
    )
}
