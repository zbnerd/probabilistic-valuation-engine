package maple.calculator.config

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Calculator pipeline configuration (Issue #1127).
 *
 * <p>기존 `workerCount` 는 parse + calc 공통. #1127 에서 parse/calc 독립 설정 가능.
 *
 * <h3>YAML</h3>
 * <pre>
 * calculator:
 *   pipeline:
 *     worker-count: 4                # legacy (parse/calc 공통)
 *     channel-capacity: 500
 *     parse-workers: 4               # NEW, default = 4
 *     calc-workers: 4                # NEW, default = 4
 *     parse-dispatcher: default      # NEW, default = Dispatchers.Default (String)
 *     calc-dispatcher: default       # NEW, default = Dispatchers.Default (String)
 * </pre>
 *
 * <p>Default 값은 기존 `workerCount` 와 동일 (4) — AC "기본값 변경 없이 기존 동작 유지" 매칭.
 * `parse-dispatcher`/`calc-dispatcher` 는 CoroutineDispatcherConverter 통해 String → CoroutineDispatcher 변환.
 */
@ConfigurationProperties("calculator.pipeline")
data class PipelineProperties(
    val workerCount: Int = 4,
    val channelCapacity: Int = 500,
    @DefaultValue("4") val parseWorkers: Int = 4,
    @DefaultValue("4") val calcWorkers: Int = 4,
    @DefaultValue val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    @DefaultValue val calcDispatcher: CoroutineDispatcher = Dispatchers.Default,
)
