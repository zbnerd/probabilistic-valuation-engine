package maple.expectation.infrastructure.batch.listener

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.stereotype.Component

/**
 * Spring Batch JobExecutionListener for logging job lifecycle metrics.
 *
 * **Functionality**
 * - Logs job start with initial metrics
 * - Logs job completion with duration and status
 * - Records execution time using Micrometer Timer
 *
 * **CLAUDE.md Compliance**
 * - Section 12: LogicExecutor pattern for exception handling
 * - Section 15: Lambda limit - extracted private methods
 * - Stateless: No mutable instance state
 */
@Component
class BatchMetricsLogger(
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) : JobExecutionListener {

    override fun beforeJob(jobExecution: JobExecution) {
        val context = TaskContext.of("Batch", "MetricsLogger", "beforeJob")

        executor.executeVoidJava({
            val jobName = jobExecution.jobInstance.jobName
            val startTime = jobExecution.startTime?.toInstant(ZoneOffset.UTC)

            if (startTime != null) {
                logJobStart(jobName, startTime)
            }
            recordJobStartMetrics(jobExecution)
        }, context)
    }

    override fun afterJob(jobExecution: JobExecution) {
        val context = TaskContext.of("Batch", "MetricsLogger", "afterJob")

        executor.executeVoidJava({
            val jobName = jobExecution.jobInstance.jobName
            val status = jobExecution.status.name
            val startTime = jobExecution.startTime?.toInstant(ZoneOffset.UTC)
            val endTime = jobExecution.endTime?.toInstant(ZoneOffset.UTC)

            if (startTime != null && endTime != null) {
                val duration = calculateDuration(startTime, endTime)
                recordJobTimer(jobName, status, duration)
                logJobCompletion(jobName, status, duration, jobExecution)
            }
        }, context)
    }

    /** Calculate job execution duration. */
    private fun calculateDuration(startTime: Instant, endTime: Instant): Duration = Duration.between(startTime, endTime)

    /** Record job execution time using Micrometer Timer. */
    private fun recordJobTimer(jobName: String, status: String, duration: Duration) {
        Timer.builder(TIMER_NAME)
            .tag(TAG_JOB_NAME, jobName)
            .tag(TAG_STATUS, status)
            .description("Batch job execution duration")
            .register(meterRegistry)
            .record(duration)
    }

    /** Log job start message. */
    private fun logJobStart(jobName: String, startTime: Instant) {
        log.info("[BatchMetricsLogger] Job started: {} at {}", jobName, startTime)
    }

    /** Record job start metrics. */
    private fun recordJobStartMetrics(jobExecution: JobExecution) {
        val jobName = jobExecution.jobInstance.jobName

        meterRegistry.counter("batch.equipment.jobs.started", "job_name", jobName).increment()

        log.debug("[BatchMetricsLogger] Job start metrics recorded for: {}", jobName)
    }

    /** Log job completion message with status and duration. */
    private fun logJobCompletion(
        jobName: String,
        status: String,
        duration: Duration,
        jobExecution: JobExecution,
    ) {
        log.info(
            "[BatchMetricsLogger] Job completed: {} with status {} in {}ms",
            jobName,
            status,
            duration.toMillis(),
        )

        if (jobExecution.status.isUnsuccessful) {
            log.error(
                "[BatchMetricsLogger] Job failed with exceptions: {}",
                jobExecution.failureExceptions,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BatchMetricsLogger::class.java)
        private const val TIMER_NAME = "batch.equipment.refresh.duration"
        private const val TAG_JOB_NAME = "job_name"
        private const val TAG_STATUS = "status"
    }
}
