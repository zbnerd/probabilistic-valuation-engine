package maple.restcontroller.controller

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.restcontroller.advice.RestControllerExceptionHandler
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.read.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ExpectationV6ControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var buffer: LocalRequestBuffer
    private lateinit var registry: InflightRequestRegistry
    private lateinit var facade: ExpectationReadFacade
    private val properties = V6ReadProperties().apply {
        requestTimeoutMs = 100
        queueCapacity = 10
        maxBatchSize = 200
        batchWindowMs = 10
        shutdownDrainTimeoutSeconds = 5
    }

    @BeforeEach
    fun setup() {
        buffer = LocalRequestBuffer(properties.queueCapacity)
        registry = InflightRequestRegistry()
        val metrics = V6ReadMetrics(SimpleMeterRegistry(), buffer, registry)
        facade = ExpectationReadFacade(registry, buffer, metrics)

        mockMvc = MockMvcBuilders
            .standaloneSetup(ExpectationV6Controller(facade, properties))
            .setControllerAdvice(RestControllerExceptionHandler())
            .build()
    }

    @Test
    fun `should buffer valid request`() {
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "진격캐넌"))
            .andExpect(status().isOk)

        assertThat(buffer.size()).isEqualTo(1)
        assertThat(registry.size()).isEqualTo(1)
    }

    @Test
    fun `should deduplicate concurrent requests for same ign`() {
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "진격캐넌"))
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "진격캐넌"))

        assertThat(buffer.size()).isEqualTo(1)
    }

    @Test
    fun `should buffer multiple different igns`() {
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "user1"))
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "user2"))

        assertThat(buffer.size()).isEqualTo(2)
    }
}
