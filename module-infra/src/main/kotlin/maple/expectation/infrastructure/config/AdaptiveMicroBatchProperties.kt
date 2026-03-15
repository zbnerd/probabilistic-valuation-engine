package maple.expectation.infrastructure.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.validation.annotation.Validated

/**
 * 적응형 마이크로 배칭(Adaptive Micro-Batching) 설정 프로퍼티
 *
 * <h3>설계 의도</h3>
 *
 * <ul>
 *   <li>Fast Lane: Semaphore permits로 동시 DB 쿼리 제한</li>
 *   <li>Batch Lane: Channel 기반 요청 적재 후 IN 쿼리로 일괄 처리</li>
 *   <li>Cache Stampede 방어: DB 커넥션 고갈 방지</li>
 * </ul>
 *
 * <h3>application.yml 설정 예시</h3>
 *
 * <pre>
 * adaptive-micro-batch:
 *   semaphore-permits: 10
 *   batch-max-wait-ms: 10
 *   batch-max-size: 50
 *   chunk-size: 100
 *   request-timeout-ms: 500
 * </pre>
 *
 * @see maple.expectation.infrastructure.batch.AdaptiveMicroBatchUserService
 * @see <a href="https://github.com/zbnerd/probabilistic-valuation-engine/issues/588">Issue #588</a>
 */
@Validated
@ConfigurationProperties(prefix = "adaptive-micro-batch")
data class AdaptiveMicroBatchProperties(
    /**
     * Semaphore permits (Fast Lane 동시 실행 수)
     *
     * <p>DB 커넥션 풀 크기를 고려하여 설정.
     * 기본값 10은 HikariCP 기본 풀 크기(10)와 일치.
     */
    @DefaultValue("10") @Min(1) @Max(100)
    val semaphorePermits: Int = 10,

    /**
     * 배치 최대 대기 시간 (ms)
     *
     * <p>첫 요청 도달 후 추가 요청을 수집하기 위해 대기하는 최대 시간.
     * 너무 길면 지연 증가, 너무 짧으면 배치 효율 감소.
     */
    @DefaultValue("10") @Min(1) @Max(100)
    val batchMaxWaitMs: Long = 10,

    /**
     * 배치 최대 크기
     *
     * <p>이 개수에 도달하면 즉시 배치 실행 (대기 시간 무시).
     */
    @DefaultValue("50") @Min(10) @Max(100)
    val batchMaxSize: Int = 50,

    /**
     * IN 쿼리 최대 파라미터 수 (Chunk Size)
     *
     * <p>PostgreSQL IN 절 파라미터 제한 고려.
     * 너무 크면 쿼리 플래너가 비효율적인 계획 수립 가능.
     */
    @DefaultValue("100") @Min(10) @Max(100)
    val chunkSize: Int = 100,

    /**
     * 요청 타임아웃 (ms)
     *
     * <p>Batch Lane에서 대기하는 최대 시간.
     * 초과 시 TimeoutException 발생 (Fail-Fast).
     */
    @DefaultValue("500") @Min(100) @Max(5000)
    val requestTimeoutMs: Long = 500,
) {
    companion object {
        /** 테스트 및 기본 설정용 팩토리 메서드 */
        fun defaults() = AdaptiveMicroBatchProperties()
    }
}
