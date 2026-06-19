package maple.calculator.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class ChunkParserMetrics(private val meterRegistry: MeterRegistry) {

    fun recordsEmitted(source: String): Counter = Counter.builder("chunk_parser_records_emitted_total")
        .tags(Tags.of("source", source))
        .register(meterRegistry)

    fun recordsSkipped(source: String): Counter = Counter.builder("chunk_parser_records_skipped_total")
        .tags(Tags.of("source", source))
        .register(meterRegistry)

    fun parseDuration(source: String): Timer = Timer.builder("chunk_parser_duration_seconds")
        .tags(Tags.of("source", source))
        .register(meterRegistry)
}
