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

    /** LikeSync Worker 설정 */
    var likeSync: WorkerSettings = WorkerSettings()

    /** Donation Worker 설정 */
    var donation: WorkerSettings = WorkerSettings()

    data class CommonSettings(
        /** 폴링 간격 (ms) */
        var pollingIntervalMs: Long = 1000,

        /** 배치 사이즈 */
        var batchSize: Int = 10,

        /** 최대 재시도 횟수 */
        var maxRetries: Int = 3,

        /** Visibility Timeout (초) */
        var visibilityTimeoutSec: Int = 30,
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
