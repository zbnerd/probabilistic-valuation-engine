package maple.calculator.cleanup

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch

class InternalApiControllerTest {

    private lateinit var mockMvc: MockMvc
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    @BeforeEach
    fun setUp() {
        val cleanupScheduler: CalculatorResultCleanupScheduler = mock()
        val controller = InternalApiController(cleanupScheduler)
        mockMvc = standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Test
    fun `POST trigger result-cleanup returns 202`() {
        mockMvc.perform(post("/api/internal/trigger/result-cleanup"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("STARTED"))
    }

    @Test
    fun `POST trigger result-cleanup returns 409 when already running`() {
        val latch = CountDownLatch(1)
        val blockingScheduler: CalculatorResultCleanupScheduler = mock()
        whenever(blockingScheduler.cleanup()).thenAnswer { latch.await(); null }
        val controller = InternalApiController(blockingScheduler)
        val testMvc = standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()

        try {
            testMvc.perform(post("/api/internal/trigger/result-cleanup"))
                .andExpect(status().isAccepted)
            testMvc.perform(post("/api/internal/trigger/result-cleanup"))
                .andExpect(status().isConflict)
        } finally {
            latch.countDown()
        }
    }
}
