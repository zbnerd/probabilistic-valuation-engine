package maple.externalapi.runstatus

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import maple.externalapi.scheduler.ExternalApiScheduler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import java.time.Instant

class InternalApiControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var runStatusTracker: RunStatusTracker
    private lateinit var scheduler: ExternalApiScheduler
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    @BeforeEach
    fun setUp() {
        runStatusTracker = org.mockito.kotlin.mock()
        scheduler = org.mockito.kotlin.mock()
        val controller = InternalApiController(runStatusTracker, scheduler, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
        mockMvc = standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Test
    fun `GET run-status returns 200 with null when no run`() {
        whenever(runStatusTracker.getCurrentStatus()).thenReturn(null)
        whenever(runStatusTracker.getLastCompletedRun()).thenReturn(null)

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current").isEmpty)
            .andExpect(jsonPath("$.lastCompleted").isEmpty)
    }

    @Test
    fun `GET run-status returns current run status`() {
        val status = RunStatus(
            runId = "run-123",
            phase = PipelinePhase.OCID_LOOKUP,
            startedAt = Instant.now(),
        )
        whenever(runStatusTracker.getCurrentStatus()).thenReturn(status)
        whenever(runStatusTracker.getLastCompletedRun()).thenReturn(null)

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.runId").value("run-123"))
            .andExpect(jsonPath("$.current.phase").value("OCID_LOOKUP"))
            .andExpect(jsonPath("$.current.terminal").value(false))
    }

    @Test
    fun `GET run-status returns lastCompleted run`() {
        val completed = RunStatus(
            runId = "run-122",
            phase = PipelinePhase.COMPLETED,
            startedAt = Instant.now().minusSeconds(3600),
            completedAt = Instant.now(),
            chunksProcessed = 800,
            recordsProcessed = 600000,
        )
        whenever(runStatusTracker.getCurrentStatus()).thenReturn(null)
        whenever(runStatusTracker.getLastCompletedRun()).thenReturn(completed)

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lastCompleted.runId").value("run-122"))
            .andExpect(jsonPath("$.lastCompleted.phase").value("COMPLETED"))
            .andExpect(jsonPath("$.lastCompleted.chunksProcessed").value(800))
    }

    @Test
    fun `POST trigger daily returns 202 with generated runId`() {
        mockMvc.perform(post("/api/internal/trigger/daily"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("STARTED"))
            .andExpect(jsonPath("$.runId").isString)
    }

    @Test
    fun `POST trigger daily uses X-Airflow-Run-Id header`() {
        mockMvc.perform(post("/api/internal/trigger/daily")
                .header("X-Airflow-Run-Id", "airflow-run-42"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.runId").value("airflow-run-42"))

        verify(scheduler).triggerDailyRefresh("airflow-run-42")
    }

    @Test
    fun `POST trigger daily returns 409 when already running`() {
        whenever(runStatusTracker.getCurrentStatus()).thenReturn(
            RunStatus(runId = "run-1", phase = PipelinePhase.RANKING_FETCH, startedAt = Instant.now())
        )

        mockMvc.perform(post("/api/internal/trigger/daily"))
            .andExpect(status().isConflict)
    }
}
