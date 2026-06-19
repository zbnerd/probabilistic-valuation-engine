package maple.calculator.config

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

/**
 * CoroutineDispatcher YAML binding converter (Issue #1127).
 *
 * <p>Spring `@ConfigurationProperties` 가 Kotlin `CoroutineDispatcher` 를 직접 bind 하지 못함.
 * YAML String → `CoroutineDispatcher` 변환을 위해 custom `Converter` bean 으로 등록.
 *
 * <p>YAML 사용:
 * <pre>
 * calculator:
 *   pipeline:
 *     parse-dispatcher: io        # → Dispatchers.IO
 *     calc-dispatcher: default    # → Dispatchers.Default
 * </pre>
 *
 * <p>미명시 시 `PipelineProperties` 의 Kotlin default value (`Dispatchers.Default`) 적용 — 이 Converter 는 override 시에만 호출.
 */
@Component
class CoroutineDispatcherConverter : Converter<String, CoroutineDispatcher> {

    override fun convert(source: String): CoroutineDispatcher = when (source.lowercase()) {
        "default" -> Dispatchers.Default
        "io" -> Dispatchers.IO
        "unconfined" -> Dispatchers.Unconfined
        else -> throw IllegalArgumentException(
            "Unknown CoroutineDispatcher: '$source'. Supported: default, io, unconfined",
        )
    }
}
