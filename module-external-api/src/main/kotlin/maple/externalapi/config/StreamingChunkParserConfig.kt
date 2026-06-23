package maple.externalapi.config

import com.fasterxml.jackson.databind.ObjectMapper
import maple.common.parser.StreamingChunkParser
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(
    name = ["external-api.parser.streaming.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class StreamingChunkParserConfig {

    @Bean
    fun streamingChunkParser(objectMapper: ObjectMapper): StreamingChunkParser =
        StreamingChunkParser(objectMapper, skipMalformed = true)
}
