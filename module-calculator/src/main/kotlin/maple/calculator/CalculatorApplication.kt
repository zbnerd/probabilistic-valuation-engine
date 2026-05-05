package maple.calculator

import maple.calculator.config.PipelineProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(PipelineProperties::class)
class CalculatorApplication

fun main(args: Array<String>) {
    runApplication<CalculatorApplication>(*args)
}
