package maple.externalapi.runstatus

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Instant
import maple.externalapi.scheduler.ExternalApiScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

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

    private fun stubEmptyPerPhaseLookups() {
        whenever(runStatusTracker.getPhaseStatus(org.mockito.kotlin.any())).thenReturn(null)
        whenever(runStatusTracker.getLastCompletedForPhase(org.mockito.kotlin.any())).thenReturn(null)
        whenever(runStatusTracker.getCurrentStatus()).thenReturn(null)
        whenever(runStatusTracker.getLastCompletedRun()).thenReturn(null)
    }

    @Test
    fun `GET run-status returns 200 with null when no run`() {
        stubEmptyPerPhaseLookups()

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current").isEmpty)
            .andExpect(jsonPath("$.lastCompleted").isEmpty)
            .andExpect(jsonPath("$.slots.RANKING_FETCH").isEmpty)
            .andExpect(jsonPath("$.slots.OCID_LOOKUP").isEmpty)
            .andExpect(jsonPath("$.slots.CHARACTER_BASIC").isEmpty)
            .andExpect(jsonPath("$.slots.ITEM_EQUIPMENT").isEmpty)
            .andExpect(jsonPath("$.lastCompletedByPhase.RANKING_FETCH").isEmpty)
    }

    @Test
    fun `GET run-status returns current run status`() {
        val status = RunStatus(
            runId = "run-123",
            phase = PipelinePhase.OCID_LOOKUP,
            triggeredPhase = PipelinePhase.OCID_LOOKUP,
            startedAt = Instant.now(),
        )
        stubEmptyPerPhaseLookups()
        whenever(runStatusTracker.getCurrentStatus()).thenReturn(status)

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
            triggeredPhase = PipelinePhase.RANKING_FETCH,
            startedAt = Instant.now().minusSeconds(3600),
            completedAt = Instant.now(),
            chunksProcessed = 800,
            recordsProcessed = 600000,
        )
        stubEmptyPerPhaseLookups()
        whenever(runStatusTracker.getLastCompletedRun()).thenReturn(completed)

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lastCompleted.runId").value("run-122"))
            .andExpect(jsonPath("$.lastCompleted.phase").value("COMPLETED"))
            .andExpect(jsonPath("$.lastCompleted.chunksProcessed").value(800))
    }

    @Test
    fun `GET run-status returns slots map with all 4 triggerable phases populated`() {
        val itemSlot = RunStatus(
            runId = "run-200",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.now(),
        )
        val rankingCompleted = RunStatus(
            runId = "run-201",
            phase = PipelinePhase.COMPLETED,
            triggeredPhase = PipelinePhase.RANKING_FETCH,
            startedAt = Instant.now().minusSeconds(120),
            completedAt = Instant.now().minusSeconds(60),
            chunksProcessed = 10,
            recordsProcessed = 1000,
        )
        stubEmptyPerPhaseLookups()
        whenever(runStatusTracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(itemSlot)
        whenever(runStatusTracker.getLastCompletedForPhase(PipelinePhase.RANKING_FETCH)).thenReturn(rankingCompleted)

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.slots.RANKING_FETCH").isEmpty)
            .andExpect(jsonPath("$.slots.OCID_LOOKUP").isEmpty)
            .andExpect(jsonPath("$.slots.CHARACTER_BASIC").isEmpty)
            .andExpect(jsonPath("$.slots.ITEM_EQUIPMENT.runId").value("run-200"))
            .andExpect(jsonPath("$.slots.ITEM_EQUIPMENT.phase").value("ITEM_EQUIPMENT"))
            .andExpect(jsonPath("$.lastCompletedByPhase.RANKING_FETCH.runId").value("run-201"))
            .andExpect(jsonPath("$.lastCompletedByPhase.ITEM_EQUIPMENT").isEmpty)
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
        mockMvc.perform(
            post("/api/internal/trigger/daily")
                .header("X-Airflow-Run-Id", "airflow-run-42"),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.runId").value("airflow-run-42"))

        verify(scheduler).triggerDailyRefresh("airflow-run-42")
    }

    @Test
    fun `POST trigger daily returns 409 when RANKING_FETCH slot is occupied`() {
        whenever(runStatusTracker.hasNonTerminalRun(PipelinePhase.RANKING_FETCH)).thenReturn(
            RunStatus(
                runId = "run-1",
                phase = PipelinePhase.RANKING_FETCH,
                triggeredPhase = PipelinePhase.RANKING_FETCH,
                startedAt = Instant.now(),
            ),
        )

        mockMvc.perform(post("/api/internal/trigger/daily"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("ALREADY_RUNNING"))
            .andExpect(jsonPath("$.runId").value("run-1"))
    }

    @Test
    fun `POST trigger daily allows new run when RANKING_FETCH slot is empty`() {
        whenever(runStatusTracker.hasNonTerminalRun(PipelinePhase.RANKING_FETCH)).thenReturn(null)

        mockMvc.perform(post("/api/internal/trigger/daily"))
            .andExpect(status().isAccepted)
    }

    @Test
    fun `POST trigger phase returns 202 with runId when slot empty`() {
        val runStatusTracker = mock<RunStatusTracker>()
        val scheduler = mock<ExternalApiScheduler>()
        whenever(runStatusTracker.hasNonTerminalRun(PipelinePhase.RANKING_FETCH)).thenReturn(null)
        val syncExecutor: java.util.concurrent.ExecutorService = object : java.util.concurrent.AbstractExecutorService() {
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown(): Boolean = false
            override fun isTerminated(): Boolean = false
            override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
            override fun execute(command: Runnable) {
                command.run()
            }
        }
        val controller = InternalApiController(runStatusTracker, scheduler, syncExecutor)

        val response = controller.triggerPhase("RANKING_FETCH", null, null)
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(response.body?.get("status")).isEqualTo("STARTED")
        assertThat(response.body?.get("runId")).isNotNull

        val runId = response.body?.get("runId")!!
        verify(scheduler).triggerPhase(PipelinePhase.RANKING_FETCH, runId, null)
    }

    @Test
    fun `POST trigger phase returns 400 for invalid phase name`() {
        mockMvc.perform(post("/api/internal/trigger/phase/BOGUS_PHASE"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_PHASE"))
    }

    @Test
    fun `POST trigger phase returns 400 for OCID_LOOKUP without upstreamRunId`() {
        mockMvc.perform(post("/api/internal/trigger/phase/OCID_LOOKUP"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("MISSING_UPSTREAM"))
    }

    @Test
    fun `POST trigger phase returns 409 when slot occupied`() {
        whenever(runStatusTracker.hasNonTerminalRun(PipelinePhase.CHARACTER_BASIC)).thenReturn(
            RunStatus(
                runId = "existing-run",
                phase = PipelinePhase.CHARACTER_BASIC,
                triggeredPhase = PipelinePhase.CHARACTER_BASIC,
                startedAt = Instant.now(),
            ),
        )

        mockMvc.perform(
            post("/api/internal/trigger/phase/CHARACTER_BASIC")
                .header("X-Upstream-Run-Id", "upstream"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("ALREADY_RUNNING"))
            .andExpect(jsonPath("$.runId").value("existing-run"))
    }
}
