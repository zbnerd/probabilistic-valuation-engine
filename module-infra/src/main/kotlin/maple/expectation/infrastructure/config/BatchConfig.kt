package maple.expectation.infrastructure.config

import maple.expectation.infrastructure.batch.listener.BatchJobRecoveryListener
import maple.expectation.infrastructure.batch.listener.BatchMetricsLogger
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemReader
import org.springframework.batch.item.ItemWriter
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Spring Batch Configuration for Equipment Refresh Job (Issue #356, P2-19)
 *
 * <h3>Components</h3>
 *
 * <ul>
 *   <li>equipmentRefreshJob: Main batch job
 *   <li>ocidRefreshStep: Step for OCID refresh (chunk size: 100)
 * </ul>
 *
 * <h3>P2-19: Job Recovery Integration</h3>
 *
 * <ul>
 *   <li>BatchJobRecoveryListener tracks failures
 *   <li>BatchMetricsLogger provides metrics
 *   <li>Job injected directly into BatchJobRecoveryScheduler (no JobRegistry)
 * </ul>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 *
 * <ul>
 *   <li>Section 12: LogicExecutor 사용 (try-catch 금지)
 *   <li>Section 15: 람다 3줄 초과 시 Method Reference 추출
 *   <li>Stateless: Job/Step 상태는 Spring Batch 메타데이터에 저장
 * </ul>
 *
 * @see maple.expectation.infrastructure.batch.reader.OcidReader
 * @see maple.expectation.infrastructure.batch.writer.LowPriorityQueueWriter
 * @see maple.expectation.infrastructure.batch.listener.BatchJobRecoveryListener
 */
@Configuration
@ConditionalOnProperty(name = ["app.batch.enabled"], havingValue = "true", matchIfMissing = false)
class BatchConfig(
    private val jobRepository: JobRepository,
    private val recoveryListener: BatchJobRecoveryListener,
    private val metricsLogger: BatchMetricsLogger,
) {

    companion object {
        private const val CHUNK_SIZE = 100
    }

    /**
     * Equipment Refresh Job Bean (P2-19: Integrated with Recovery Listeners)
     *
     * <p>OCID를 조회하여 LOW Priority Queue에 추가하는 배치 Job
     *
     * <h4>P2-19 Integration</h4>
     *
     * <ul>
     *   <li>BatchJobRecoveryListener: 실패 감지 및 복구 메타데이터 저장
     *   <li>BatchMetricsLogger: 메트릭 수집
     *   <li>Job injected directly into BatchJobRecoveryScheduler (no JobRegistry)
     * </ul>
     *
     * @param ocidRefreshStep OCID 갱신 스텝
     * @return Job 인스턴스
     */
    @Bean
    fun equipmentRefreshJob(ocidRefreshStep: Step): Job = JobBuilder("equipmentRefreshJob", jobRepository)
        .start(ocidRefreshStep)
        .listener(recoveryListener)
        .listener(metricsLogger)
        .build()

    /**
     * OCID Refresh Step Bean
     *
     * <p>Chunk size: 100 (OCID 조회 후 Queue에 일괄 추가)
     *
     * <p>Note: ResourcelessTransactionManager 사용 (DB 트랜잭션 없이 Queue에만 추가)
     *
     * @param ocidReader ItemReader for OCID 조회
     * @param queueWriter ItemWriter for LOW Priority Queue 추가
     * @param transactionManager Platform Transaction Manager
     * @return Step 인스턴스
     */
    @Bean
    fun ocidRefreshStep(
        ocidReader: ItemReader<String>,
        queueWriter: ItemWriter<String>,
        transactionManager: PlatformTransactionManager,
    ): Step = StepBuilder("ocidRefreshStep", jobRepository)
        .chunk<String, String>(CHUNK_SIZE, transactionManager)
        .reader(ocidReader)
        .writer(queueWriter)
        .build()
}
