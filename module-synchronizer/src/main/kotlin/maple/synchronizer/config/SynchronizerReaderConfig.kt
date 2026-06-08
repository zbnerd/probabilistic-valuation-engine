package maple.synchronizer.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SynchronizerReaderConfig {

    @Bean
    fun basicChunkMissingFieldThreshold(
        @Value("\${synchronizer.reader.missing-field-threshold:100}") threshold: Int,
    ): Int = threshold
}
