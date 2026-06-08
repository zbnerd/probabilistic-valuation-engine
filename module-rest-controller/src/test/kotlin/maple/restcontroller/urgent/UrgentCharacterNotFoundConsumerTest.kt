package maple.restcontroller.urgent

import maple.restcontroller.read.NegativeCacheService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.kafka.support.Acknowledgment

class UrgentCharacterNotFoundConsumerTest {

    private val negativeCacheService: NegativeCacheService = mock()
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val acknowledgment: Acknowledgment = mock()
    private val consumer = UrgentCharacterNotFoundConsumer(negativeCacheService, objectMapper, 3600L)

    @Test
    fun `consume sets negative cache and acknowledges`() {
        val message = """{"userIgn":"unknownChar","reason":"OPENAPI00004"}"""

        consumer.consume(message, acknowledgment)

        verify(negativeCacheService).setNegativeCache("unknownChar", 3600L)
        verify(acknowledgment).acknowledge()
    }

    @Test
    fun `consume with missing userIgn acknowledges without setting negative cache`() {
        val message = """{"reason":"OPENAPI00004"}"""

        consumer.consume(message, acknowledgment)

        verify(negativeCacheService, never()).setNegativeCache(any(), any())
        verify(acknowledgment).acknowledge()
    }
}
