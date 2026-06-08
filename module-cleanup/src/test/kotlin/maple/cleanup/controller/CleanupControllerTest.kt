package maple.cleanup.controller

import maple.cleanup.CleanupApplication
import maple.cleanup.inbox.ConsumedChunkInbox
import maple.cleanup.inbox.InboxProperties
import maple.cleanup.service.RunCleanupService
import maple.common.cleanup.RunCleanupResult
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(CleanupController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CleanupApplication::class)
class CleanupControllerTest {
    @MockBean lateinit var consumerFactory: ConsumerFactory<String, String>

    @MockBean lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired lateinit var mockMvc: MockMvc

    @MockBean lateinit var runCleanupService: RunCleanupService

    @MockBean lateinit var inbox: ConsumedChunkInbox

    @MockBean lateinit var inboxProperties: InboxProperties

    @Test
    fun `POST cleanup-runs returns result`() {
        whenever(runCleanupService.cleanupRuns()).thenReturn(
            RunCleanupResult(runsDeleted = 3, bytesDeleted = 1024, errors = 0, throttled = 0),
        )
        mockMvc.perform(post("/api/internal/cleanup/runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runsDeleted").value(3))
            .andExpect(jsonPath("$.bytesDeleted").value(1024))
    }

    @Test
    fun `POST cleanup-calculator-runs returns result`() {
        whenever(runCleanupService.cleanupCalculatorRuns()).thenReturn(
            RunCleanupResult(runsDeleted = 5, bytesDeleted = 2048, errors = 0, throttled = 0),
        )
        mockMvc.perform(post("/api/internal/cleanup/calculator-runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runsDeleted").value(5))
    }

    @Test
    fun `POST cleanup-inbox drains and returns response with deleted and failed counts`() {
        whenever(inbox.drain()).thenReturn(emptyList())
        mockMvc.perform(post("/api/internal/cleanup/inbox"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(0))
            .andExpect(jsonPath("$.failed").value(0))
    }
}
