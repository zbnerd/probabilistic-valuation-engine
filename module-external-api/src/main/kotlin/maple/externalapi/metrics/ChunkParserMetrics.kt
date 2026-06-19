package maple.externalapi.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

/**
 * Metrics for [maple.common.parser.StreamingChunkParser] usage in
 * ext-api. Calculator has its own instance with the same metric names;
 * `application` tag (auto-set by Spring Boot Actuator) disambiguates.
 */
@Component
class ChunkParserMetrics(private val meterRegistry: MeterRegistry) {

    fun recordsEmitted(source: String): Counter =
        Counter.builder("chunk_parser_records_emitted_total")
            .tags(Tags.of("source", source))
            .register(meterRegistry)

    fun recordsSkipped(source: String): Counter =
        Counter.builder("chunk_parser_records_skipped_total")
            .tags(Tags.of("source", source))
            .register(meterRegistry)

    fun parseDuration(source: String): Timer =
        Timer.builder("chunk_parser_duration_seconds")
            .tags(Tags.of("source", source))
            .register(meterRegistry)
}