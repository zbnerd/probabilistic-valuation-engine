package maple.calculator.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("calculator.pipeline")
data class PipelineProperties(
    val workerCount: Int = 4,
    val channelCapacity: Int = 500,
)
