package maple.expectation.infrastructure.util

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class JsonMapper(
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor
) {
    private val logger = LoggerFactory.getLogger(JsonMapper::class.java)

    companion object {
        private val JSON_TRANSLATOR = ExceptionTranslator.forJson()
    }

    fun <T> readValue(json: String, clazz: Class<T>): T {
        return executor.executeWithTranslation(
            { objectMapper.readValue(json, clazz) },
            JSON_TRANSLATOR,
            TaskContext.of("Json", "ReadValue", clazz.simpleName)
        )
    }

    fun <T> readValueOrDefault(json: String, clazz: Class<T>, defaultValue: T): T {
        return executor.executeOrDefault(
            { objectMapper.readValue(json, clazz) },
            defaultValue,
            TaskContext.of("Json", "ReadValueOrDefault", clazz.simpleName)
        )
    }

    fun writeValueAsString(value: Any): String {
        return executor.executeWithTranslation(
            { objectMapper.writeValueAsString(value) },
            JSON_TRANSLATOR,
            TaskContext.of("Json", "WriteValueAsString", value.javaClass.simpleName)
        )
    }

    fun writeValueAsBytes(value: Any): ByteArray {
        return executor.executeWithTranslation(
            { objectMapper.writeValueAsBytes(value) },
            JSON_TRANSLATOR,
            TaskContext.of("Json", "WriteValueAsBytes", value.javaClass.simpleName)
        )
    }

    fun writeValueAsPrettyString(value: Any): String {
        return executor.executeWithTranslation(
            { objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) },
            JSON_TRANSLATOR,
            TaskContext.of("Json", "WriteValueAsPrettyString", value.javaClass.simpleName)
        )
    }

    fun writeValueAsStringOrDefault(value: Any, defaultValue: String): String {
        return executor.executeOrDefault(
            { objectMapper.writeValueAsString(value) },
            defaultValue,
            TaskContext.of("Json", "WriteValueAsStringOrDefault", value.javaClass.simpleName)
        )
    }
}
