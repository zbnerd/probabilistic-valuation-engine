package maple.synchronizer.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class SynchronizerReaderMetrics(private val registry: MeterRegistry) {

    private val parseErrorCounters = mutableMapOf<String, Counter>()
    private val missingFieldCounters = mutableMapOf<String, Counter>()
    private val filteredCounters = mutableMapOf<String, Counter>()

    fun incrementParseError(reader: String) {
        parseErrorCounters
            .getOrPut(reader) { registry.counter("synchronizer_reader_parse_error_total", "reader", reader) }
            .increment()
    }

    fun incrementMissingField(reader: String) {
        missingFieldCounters
            .getOrPut(reader) { registry.counter("synchronizer_reader_missing_field_total", "reader", reader) }
            .increment()
    }

    fun incrementFiltered(reader: String, reason: String) {
        filteredCounters
            .getOrPut("${reader}:${reason}") {
                registry.counter("synchronizer_reader_filtered_total", "reader", reader, "reason", reason)
            }
            .increment()
    }
}
