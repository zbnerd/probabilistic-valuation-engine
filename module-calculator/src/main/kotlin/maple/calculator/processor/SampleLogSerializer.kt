package maple.calculator.processor

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import maple.calculator.model.CalculationResult
import org.springframework.stereotype.Component

/** Wraps `ObjectMapper.writeValueAsString` so the processor stays free of JSON concerns. */
@Component
class SampleLogSerializer(
    private val objectMapper: ObjectMapper,
) {
    /** Format a calculation result for the sample-debug log. Returns the input's
     *  `toString()` representation if serialization fails so logging never throws. */
    fun serialize(result: CalculationResult): String = try {
        objectMapper.writeValueAsString(result)
    } catch (ex: JsonProcessingException) {
        "<<unserializable: ${ex.originalMessage}>> ${result.ocid}:${result.presetNo}"
    }
}
