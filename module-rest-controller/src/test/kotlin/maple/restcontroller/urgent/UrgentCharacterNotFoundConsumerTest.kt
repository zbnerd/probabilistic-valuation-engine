package maple.restcontroller.urgent

import maple.restcontroller.read.ReadModelCacheService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.kafka.support.Acknowledgment

class UrgentCharacterNotFoundConsumerTest {

    private val cacheService: ReadModelCacheService = mock()
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val acknowledgment: Acknowledgment = mock()
    private val consumer = UrgentCharacterNotFoundConsumer(cacheService, objectMapper, 3600L)

    @Test
    fun `consume sets negative cache and acknowledges`() {
        val message = """{"userIgn":"unknownChar","reason":"OPENAPI00004"}"""

        consumer.consume(message, acknowledgment)

        verify(cacheService).setNegativeCache("unknownChar", 3600L)
        verify(acknowledgment).acknowledge()
    }
}
