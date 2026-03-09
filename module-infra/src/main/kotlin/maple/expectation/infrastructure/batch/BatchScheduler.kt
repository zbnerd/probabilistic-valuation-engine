package maple.expectation.infrastructure.batch

import maple.expectation.error.exception.InternalSystemException
import maple.expectation.infrastructure.executor.CheckedLogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Spring Batch Scheduler for Equipment Refresh Job
 *
 * **기능**
 * - 매일 새벽 2시에 전체 유저 장비 데이터 갱신 Job 실행
 * - JobParameters에 timestamp 추가 (Job 재실행 가능하도록)
 * - Cron 표현식은 application.yml에서 설정 가능
 *
 * **Cron 표현식**
 * `0 0 2 * * *` (초 분 시 일 월 요일)
 *
 * **CLAUDE.md 준수사항**
 * - Section 12: LogicExecutor 사용 (예외 처리)
 * - Section 15: 람다 3줄 초과 시 Method 추출
 * - Stateless: 상태 없음
 * - 메서드 참조 우선: JobLauncher::run 형태
 *
 * @see [Spring Batch Documentation](https://docs.spring.io/spring-batch/docs/current/reference/html/index.html)
 */
@Component
@ConditionalOnBean(JobLauncher::class)
class BatchScheduler(
    private val jobLauncher: JobLauncher,
    private val equipmentRefreshJob: Job,
    private val checkedExecutor: CheckedLogicExecutor,
    @Value("\${batch.equipment-refresh.cron:0 0 2 * * *}") private val cronExpression: String = "0 0 2 * * *",
) {

    /**
     * 매일 새벽 2시에 전체 유저 장비 데이터 갱신
     * Cron: 0 0 2 * * * (초 분 시 일 월 요일)
     * JobParameters에 timestamp를 추가하여 Spring Batch가 매번 새로운 JobInstance로 인식하도록 합니다.
     */
    @Scheduled(cron = "\${batch.equipment-refresh.cron:0 0 2 * * *}")
    fun runEquipmentRefreshJob() {
        val context = TaskContext.of("BatchScheduler", "EquipmentRefresh")

        log.info("[BatchScheduler] Starting equipment refresh job with cron: {}", cronExpression)
        checkedExecutor.executeUncheckedVoid({ launchJob() }, context, BATCH_EXCEPTION_MAPPER)
    }

    /** Job 실행 (Method Extraction - Section 15: Lambda Hell 방지) */
    @Throws(Exception::class)
    private fun launchJob() {
        val params = JobParametersBuilder()
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters()

        log.debug("[BatchScheduler] Launching job with params: {}", params)
        jobLauncher.run(equipmentRefreshJob, params)
        log.info("[BatchScheduler] Equipment refresh job launched successfully")
    }

    companion object {
        private val log = LoggerFactory.getLogger(BatchScheduler::class.java)

        // Checked exception to RuntimeException mapper
        private val BATCH_EXCEPTION_MAPPER: (Exception) -> RuntimeException =
            { e -> InternalSystemException("BatchScheduler:LaunchJob", e) }
    }
}
