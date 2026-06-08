package maple.expectation.infrastructure.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.validation.annotation.Validated

/**
 * Thread Pool 설정 외부화 (P1-1, P2-25)
 *
 * <h3>설정 경로</h3>
 *
 * <pre>
 * executor:
 *   equipment:
 *     core-pool-size: 8
 *     max-pool-size: 16
 *     queue-capacity: 200
 *   preset:
 *     core-pool-size: 12
 *     max-pool-size: 24
 *     queue-capacity: 100
 *   alert:
 *     core-pool-size: 2
 *     max-pool-size: 4
 *     queue-capacity: 200
 *   expectation:
 *     core-pool-size: 4
 *     max-pool-size: 8
 *     queue-capacity: 200
 *   operational:
 *     core-pool-size: 8
 *     max-pool-size: 16
 *     queue-capacity: 200
 *   backfill:
 *     core-pool-size: 4
 *     max-pool-size: 8
 *     queue-capacity: 500
 * </pre>
 *
 * <h3>CPU 기반 설정 가이드라인 (P2-25)</h3>
 *
 * <table>
 *   <tr><th>CPU Core</th><th>I/O-bound (equipment)</th><th>CPU-bound (preset)</th><th>Background (alert)</th></tr>
 *   <tr><td>2 vCPU</td><td>8:16 (4×)</td><td>6:12 (3×)</td><td>2:4 (1×)</td></tr>
 *   <tr><td>4 vCPU</td><td>16:32 (4×)</td><td>12:24 (3×)</td><td>2:4 (1×)</td></tr>
 *   <tr><td>8 vCPU</td><td>32:64 (4×)</td><td>24:48 (3×)</td><td>2:4 (1×)</td></tr>
 * </table>
 *
 * <h4>설정 규칙</h4>
 * <ul>
 *   <li><b>1:2 core:max 비율 강제</b>: 모든 executor는 1:2 비율 준수
 *   <li><b>I/O-bound</b>: 네트워크/DB 호출 위주 → CPU × 4 배수
 *   <li><b>CPU-bound</b>: 계산 작업 위주 → CPU × 3 배수
 *   <li><b>Background</b>: 저우선도 작업 → 최소 설정 (2:4)
 * </ul>
 *
 * <h3>프로필별 설정</h3>
 *
 * <ul>
 *   <li>local: 개발 환경 기본값
 *   <li>prod: t3.small 2vCPU 기준 최적화
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "executor")
data class ExecutorProperties(
    @DefaultValue val equipment: PoolConfig = PoolConfig(),
    @DefaultValue val preset: PoolConfig = PoolConfig(),
    @DefaultValue val alert: PoolConfig = PoolConfig(),
    @DefaultValue val expectation: ExpectationConfig = ExpectationConfig(),
    @DefaultValue val async: PoolConfig = PoolConfig(),
    @DefaultValue val operational: PoolConfig = PoolConfig(),
    @DefaultValue val backfill: PoolConfig = PoolConfig(),
    @DefaultValue val item: PoolConfig = PoolConfig(corePoolSize = 4, maxPoolSize = 8, queueCapacity = 200),
) {
    /**
     * 개별 Thread Pool 설정
     *
     * <p><b>P2-25 표준화 규칙:</b> corePoolSize:maxPoolSize는 항상 1:2 비율 유지
     *
     * @property corePoolSize 코어 스레드 수 (기본값: equipment=8, preset=12, alert=2, expectation=4)
     * @property maxPoolSize 최대 스레드 수 (기본값: equipment=16, preset=24, alert=4, expectation=8)
     * @property queueCapacity 큐 용량 (기본값: equipment=200, preset=100, alert=200, expectation=200)
     */
    data class PoolConfig(
        @DefaultValue("8") @Min(1) @Max(64) var corePoolSize: Int = 8,
        @DefaultValue("16") @Min(1) @Max(128) var maxPoolSize: Int = 16,
        @DefaultValue("200") @Min(10) @Max(5000) var queueCapacity: Int = 200,
    ) {
        /**
         * P2-25: 1:2 core:max 비율 검증
         *
         * @throws IllegalStateException 비율이 1:2가 아닐 경우
         */
        fun validateRatio(name: String) {
            require(maxPoolSize == corePoolSize * 2) {
                "[ExecutorProperties] $name executor violates 1:2 core:max ratio (core=$corePoolSize, max=$maxPoolSize). " +
                    "P2-25 requires maxPoolSize = corePoolSize × 2"
            }
        }
    }

    /**
     * Expectation compute executor 설정 wrapper (IO + CPU 분리)
     *
     * <p>기존 `expectation: PoolConfig` 를 두 개의 sub-pool 로 분리:
     * <ul>
     *   <li>computeIo: IO-bound 외부 호출/DB read/write (legacy sizing 유지)
     *   <li>computeCpu: CPU-bound JSON parse/serialization/계산
     * </ul>
     *
     * <p>YAML:
     * <pre>
     * executor:
     *   expectation:
     *     compute-io:
     *       core-pool-size: 4
     *       max-pool-size: 8
     *       queue-capacity: 200
     *     compute-cpu:
     *       core-pool-size: 4
     *       max-pool-size: 8
     *       queue-capacity: 1000
     * </pre>
     */
    data class ExpectationConfig(
        @DefaultValue val computeIo: PoolConfig = PoolConfig(corePoolSize = 4, maxPoolSize = 8, queueCapacity = 200),
        @DefaultValue val computeCpu: PoolConfig = PoolConfig(corePoolSize = 4, maxPoolSize = 8, queueCapacity = 1000),
    )

    /**
     * 전체 설정 검증 (P2-25)
     *
     * @throws IllegalStateException 비율 위반 시
     */
    fun validateAll() {
        equipment.validateRatio("equipment")
        preset.validateRatio("preset")
        alert.validateRatio("alert")
        expectation.computeIo.validateRatio("expectation.compute-io")
        expectation.computeCpu.validateRatio("expectation.compute-cpu")
        async.validateRatio("async")
        operational.validateRatio("operational")
        backfill.validateRatio("backfill")
        item.validateRatio("item")
    }
}
