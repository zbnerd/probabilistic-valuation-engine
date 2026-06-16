package maple.calculator.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Calculator pipeline configuration (Issue #1127, #1201, #1202).
 *
 * <p>기존 `workerCount` 는 parse + calc 공통. #1127 에서 parse/calc 독립 설정 가능.
 * #1201: `workerCount` deprecate (backward compat 유지, future removal).
 * #1202: `parse-dispatcher`/`calc-dispatcher` 가 named Spring bean 으로 wiring 가능
 *         (e.g. `calculatorParseDispatcher` bean name). 기존 String (default/io/unconfined) 도 호환.
 *
 * <p>Issue #20260615-source-race: source chunk exists() 가 MinIO race 로 ~10% 확률로
 *        false 반환. `source-chunk-retry-delays-ms` 로 backoff schedule 설정.
 *        첫 element 가 0 이면 즉시 시도, 나머지는 각 재시도 사이의 delay (ms).
 *
 * <h3>YAML</h3>
 * <pre>
 * calculator:
 *   pipeline:
 *     worker-count: 4                # DEPRECATED #1201 (use parse-workers/calc-workers)
 *     channel-capacity: 500
 *     parse-workers: 4               # default = 4
 *     calc-workers: 4                # default = 4
 *     parse-dispatcher: default      # String (default/io/unconfined) or bean name
 *     calc-dispatcher: default       # String (default/io/unconfined) or bean name
 *     source-chunk-retry-delays-ms: [0, 100, 300, 1000, 3000]   # default = 5 attempts, ~4.4s
 * </pre>
 */
@ConfigurationProperties("calculator.pipeline")
data class PipelineProperties(
    @Deprecated("Issue #1201: use parseWorkers/calcWorkers. Kept for backward compat.")
    val workerCount: Int = 4,
    val channelCapacity: Int = 500,
    @DefaultValue("4") val parseWorkers: Int = 4,
    @DefaultValue("4") val calcWorkers: Int = 4,
    @DefaultValue("default") val parseDispatcher: String = "default",
    @DefaultValue("default") val calcDispatcher: String = "default",
    @DefaultValue("0,100,300,1000,3000")
    val sourceChunkRetryDelaysMs: List<Long> = listOf(0L, 100L, 300L, 1000L, 3000L),
)
