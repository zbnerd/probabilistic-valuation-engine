package maple.expectation.infrastructure.pgmq

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * PGMQ Worker 설정 (ADR-002)
 *
 * <h3>역할</h3>
 * <p>PGMQ Worker들의 공통 설정 관리
 *
 * <h3>설정 항목</h3>
 * <ul>
 *   <li>폴링 간격 (기본값: 1000ms)
 *   <li>배치 사이즈 (기본값: 10)
 *   <li>최대 재시도 횟수 (기본값: 3)
 *   <li>Visibility Timeout (기본값: 30초)
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "pgmq.worker")
class PgmqWorkerConfig {

    /** 공통 Worker 설정 */
    var common: CommonSettings = CommonSettings()

    /** Calculation Worker 설정 */
    var calculation: WorkerSettings = WorkerSettings()

    /** Donation Worker 설정 */
    var donation: WorkerSettings = WorkerSettings()

    /** Nexon Collector 설정 */
    var nexonCollector: WorkerSettings = WorkerSettings(enabled = true)

    /** Expectation Calc High Priority Worker 설정 */
    var expectationCalcHigh: WorkerSettings = WorkerSettings()

    /** Expectation Calc Low Priority Worker 설정 */
    var expectationCalcLow: WorkerSettings = WorkerSettings()

    /** Nexon FanOut Worker 설정 (429 재시도 전용) */
    var nexonFanout: WorkerSettings = WorkerSettings()

    /** External API Worker 설정 (OCID + Equipment + input/snapshot staging) */
    var externalApi: WorkerSettings = WorkerSettings()

    /** CPU-bound Calculation Worker 설정 */
    var calculationRequested: WorkerSettings = WorkerSettings()

    /** DB-bound Result Persist Worker 설정 */
    var calculationCompleted: WorkerSettings = WorkerSettings()

    data class CommonSettings(
        /** 폴링 간격 (ms) (ADR-355) */
        var pollingIntervalMs: Long = 300,

        /** 배치 사이즈 (ADR-355) */
        var batchSize: Int = 50,

        /** 최대 재시도 횟수 */
        var maxRetries: Int = 3,

        /** Visibility Timeout (초) (ADR-355: batch × avg latency) */
        var visibilityTimeoutSec: Int = 120,

        /** Pipeline micro-batch size for drain */
        var pipelineMicroBatchSize: Int = 10,
        /** Pipeline drain interval (ms) */
        var pipelineDrainIntervalMs: Long = 100,

        /** Worker pool size (replaces Virtual Thread). Default: availableProcessors * 2 */
        var workerPoolSize: Int = Runtime.getRuntime().availableProcessors() * 2,

        /** Sequential batch accumulation window (ms). 0 = parallel mode (default), >0 = sequential mode (#743) */
        var sequentialBatchMs: Long = 0,
    )

    data class WorkerSettings(
        /** Worker 활성화 여부 */
        var enabled: Boolean = false,

        /** 폴링 간격 (ms) - null이면 common 설정 사용 */
        var pollingIntervalMs: Long? = null,

        /** 배치 사이즈 - null이면 common 설정 사용 */
        var batchSize: Int? = null,

        /** 최대 재시도 횟수 - null이면 common 설정 사용 */
        var maxRetries: Int? = null,
    )
}
