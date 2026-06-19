package maple.calculator.config

import com.fasterxml.jackson.databind.ObjectMapper
import maple.common.parser.StreamingChunkParser
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ChunkParserConfig {

    @Bean
    fun streamingChunkParser(objectMapper: ObjectMapper): StreamingChunkParser = StreamingChunkParser(objectMapper, skipMalformed = true)
}
