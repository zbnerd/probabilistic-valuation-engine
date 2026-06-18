package maple.externalapi.runstatus

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import maple.externalapi.loop.PhaseLoopController
import maple.externalapi.scheduler.ExternalApiScheduler
import maple.externalapi.scheduler.PhaseStopSignal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
    private lateinit var stopSignal: PhaseStopSignal
    private lateinit var phaseLoopController: PhaseLoopController
    private lateinit var controller: InternalApiController
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    @BeforeEach
    fun setUp() {
        runStatusTracker = mock()
        scheduler = mock()
        stopSignal = PhaseStopSignal()
        phaseLoopController = mock()
        // Default: no active loops
        whenever(phaseLoopController.hasActiveLoop(any())).thenReturn(false)
        whenever(phaseLoopController.getLoopState(any())).thenReturn(null)
        whenever(phaseLoopController.activeLoops()).thenReturn(emptyList())
        // Wire scheduler.requestPhaseStop so it actually trips the shared stopSignal.
        whenever(scheduler.requestPhaseStop(any())).thenAnswer { invocation ->
            val phase = invocation.getArgument<maple.externalapi.runstatus.PipelinePhase>(0)
            val hadNonTerminal = runStatusTracker.hasNonTerminalRun(phase) != null
            if (hadNonTerminal) {
                stopSignal.requestStop(phase)
            }
            hadNonTerminal || stopSignal.isStopRequested(phase)
        }
        controller = InternalApiController(
            runStatusTracker,
            scheduler,
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
            phaseLoopController,
        )
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
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `POST trigger daily returns 202 immediately without blocking on phase completion`() {
        // Capture the Future returned by submit() and assert it completes
        // within a short window. With the .join() bug, the runnable blocks
        // on a never-completing CF, so the Future never completes.
        val capturedFutures = java.util.concurrent.ConcurrentLinkedQueue<java.util.concurrent.Future<*>>()
        val capturingExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-capturing").apply { isDaemon = true }
        }
        // Wrap submit via reflection-free override: use a delegating executor.
        // Easiest path: subclass to override submit(Runnable).
        val instrumentedExecutor = object : java.util.concurrent.AbstractExecutorService() {
            override fun shutdown() = capturingExecutor.shutdown()
            override fun shutdownNow(): MutableList<Runnable> = capturingExecutor.shutdownNow()
            override fun isShutdown(): Boolean = capturingExecutor.isShutdown
            override fun isTerminated(): Boolean = capturingExecutor.isTerminated
            override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
                capturingExecutor.awaitTermination(timeout, unit)
            override fun execute(command: Runnable) {
                capturedFutures.add(capturingExecutor.submit(command))
            }
        }
        try {
            val neverCompleting = CompletableFuture<Void>()
            // triggerDailyRefresh takes 1 arg (airflowRunId: String?). any() matches non-null,
            // so we don't need anyOrNull here — but the controller may pass null when no
            // X-Airflow-Run-Id header is set, so we use anyOrNull for safety.
            whenever(scheduler.triggerDailyRefresh(anyOrNull())).thenReturn(neverCompleting)

            val syncController = InternalApiController(
                runStatusTracker, scheduler, instrumentedExecutor, phaseLoopController,
            )
            val syncMockMvc = standaloneSetup(syncController)
                .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
                .build()

            syncMockMvc.perform(post("/api/internal/trigger/daily"))
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.status").value("STARTED"))

            val future = capturedFutures.peek()
                ?: error("expected exactly one submitted task; queue was empty")
            // Fire-and-forget: the runnable must return quickly. With .join()
            // on a never-completing CF, the runnable blocks indefinitely.
            val completed = try {
                future.get(500, TimeUnit.MILLISECONDS)
                true
            } catch (ex: java.util.concurrent.TimeoutException) {
                false
            }
            assertThat(completed)
                .withFailMessage(
                    "executor.submit() future should be done immediately (fire-and-forget); " +
                        "it never completed, which means the controller still .join()s on the phase CF."
                )
                .isTrue()
        } finally {
            capturingExecutor.shutdownNow()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `POST trigger phase returns 202 immediately without blocking on phase completion`() {
        val capturedFutures = java.util.concurrent.ConcurrentLinkedQueue<java.util.concurrent.Future<*>>()
        val capturingExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-capturing").apply { isDaemon = true }
        }
        val instrumentedExecutor = object : java.util.concurrent.AbstractExecutorService() {
            override fun shutdown() = capturingExecutor.shutdown()
            override fun shutdownNow(): MutableList<Runnable> = capturingExecutor.shutdownNow()
            override fun isShutdown(): Boolean = capturingExecutor.isShutdown
            override fun isTerminated(): Boolean = capturingExecutor.isTerminated
            override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
                capturingExecutor.awaitTermination(timeout, unit)
            override fun execute(command: Runnable) {
                capturedFutures.add(capturingExecutor.submit(command))
            }
        }
        try {
            val neverCompleting = CompletableFuture<Void>()
            // Stub: any 4-arg call (with loopId defaulting to null) returns never-completing CF
            whenever(scheduler.triggerPhase(any(), any(), any(), anyOrNull())).thenReturn(neverCompleting)

            val syncController = InternalApiController(
                runStatusTracker, scheduler, instrumentedExecutor, phaseLoopController,
            )
            val syncMockMvc = standaloneSetup(syncController)
                .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
                .build()

            syncMockMvc.perform(
                post("/api/internal/trigger/phase/RANKING_FETCH")
                    .header("X-Upstream-Run-Id", "u-1"),
            )
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.status").value("STARTED"))

            val future = capturedFutures.peek()
                ?: error("expected exactly one submitted task; queue was empty")
            val completed = try {
                future.get(500, TimeUnit.MILLISECONDS)
                true
            } catch (ex: java.util.concurrent.TimeoutException) {
                false
            }
            assertThat(completed)
                .withFailMessage(
                    "executor.submit() future should be done immediately (fire-and-forget); " +
                        "it never completed, which means the controller still .join()s on the phase CF."
                )
                .isTrue()
        } finally {
            capturingExecutor.shutdownNow()
        }
    }

    @Test
    fun `POST trigger phase returns 202 with runId when slot empty`() {
        val runStatusTracker = mock<RunStatusTracker>()
        val scheduler = mock<ExternalApiScheduler>()
        val phaseLoopController = mock<PhaseLoopController>()
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
        val controller = InternalApiController(runStatusTracker, scheduler, syncExecutor, phaseLoopController)

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

    @Test
    fun `POST stop phase returns 202 STOP_REQUESTED when phase is running`() {
        val slot = RunStatus(
            runId = "run-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.now(),
        )
        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        whenever(runStatusTracker.hasNonTerminalRun(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(slot)
        whenever(runStatusTracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(slot)

        val response = controller.stopPhase(
            phaseName = "ITEM_EQUIPMENT",
            airflowRunId = "airflow-corr-1",
        )

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        val body = response.body!!
        assertEquals("STOP_REQUESTED", body["status"])
        assertEquals("ITEM_EQUIPMENT", body["phase"])
        assertEquals("run-1", body["runId"])
        assertEquals("airflow-corr-1", body["airflowRunId"])
        assertTrue(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `POST stop phase returns 200 NOT_RUNNING when slot empty`() {
        whenever(runStatusTracker.hasNonTerminalRun(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(null)
        whenever(runStatusTracker.getLastCompletedForPhase(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(null)

        val response = controller.stopPhase(
            phaseName = "ITEM_EQUIPMENT",
            airflowRunId = null,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertEquals("NOT_RUNNING", body["status"])
        assertEquals("ITEM_EQUIPMENT", body["phase"])
    }

    @Test
    fun `POST stop phase returns 200 NOT_RUNNING when slot is terminal`() {
        val terminal = RunStatus(
            runId = "run-old",
            phase = PipelinePhase.COMPLETED,
            triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.now().minusSeconds(60),
            completedAt = Instant.now(),
        )
        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-old")
        runStatusTracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, "run-old", 0, 0L)
        whenever(runStatusTracker.hasNonTerminalRun(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(null)
        whenever(runStatusTracker.getLastCompletedForPhase(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(terminal)

        val response = controller.stopPhase(
            phaseName = "ITEM_EQUIPMENT",
            airflowRunId = null,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("NOT_RUNNING", response.body!!["status"])
    }

    @Test
    fun `POST stop phase returns 400 INVALID_PHASE for unknown name`() {
        val response = controller.stopPhase(
            phaseName = "BOGUS",
            airflowRunId = null,
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("INVALID_PHASE", response.body!!["error"])
    }

    @Test
    fun `POST stop phase returns 400 INVALID_PHASE for non-triggerable phase`() {
        val response = controller.stopPhase(
            phaseName = "COMPLETED",
            airflowRunId = null,
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("INVALID_PHASE", response.body!!["error"])
    }

    @Test
    fun `POST stop phase on phase A does not affect phase B running`() {
        val slot = RunStatus(
            runId = "run-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.now(),
        )
        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        whenever(runStatusTracker.hasNonTerminalRun(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(slot)
        whenever(runStatusTracker.hasNonTerminalRun(PipelinePhase.OCID_LOOKUP)).thenReturn(null)

        controller.stopPhase(phaseName = "OCID_LOOKUP", airflowRunId = null)

        // OCID_LOOKUP is not running → NOT_RUNNING, and ITEM_EQUIPMENT flag not set
        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
        assertFalse(stopSignal.isStopRequested(PipelinePhase.OCID_LOOKUP))
    }

    @Test
    fun `POST loop phase ITEM_EQUIPMENT returns 202 LOOP_STARTED with generated loopId`() {
        whenever(phaseLoopController.startLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(
            LoopState(
                loopId = "L-1",
                phase = PipelinePhase.ITEM_EQUIPMENT,
                startedAt = Instant.now(),
            ),
        )

        mockMvc.perform(post("/api/internal/loop/phase/ITEM_EQUIPMENT"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("LOOP_STARTED"))
            .andExpect(jsonPath("$.phase").value("ITEM_EQUIPMENT"))
            .andExpect(jsonPath("$.loopId").value("L-1"))
            .andExpect(jsonPath("$.iterationCount").value(0))
    }

    @Test
    fun `POST loop phase RANKING_FETCH returns 400 INVALID_PHASE`() {
        mockMvc.perform(post("/api/internal/loop/phase/RANKING_FETCH"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_PHASE"))
    }

    @Test
    fun `POST loop phase BOGUS returns 400 INVALID_PHASE`() {
        mockMvc.perform(post("/api/internal/loop/phase/BOGUS"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_PHASE"))
    }

    @Test
    fun `POST stop loop phase ITEM_EQUIPMENT while loop active returns 202 STOP_REQUESTED`() {
        val state = LoopState(
            loopId = "L-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.now(),
            iterationCount = 3,
            lastRunId = "run-3",
        )
        whenever(phaseLoopController.stopLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(state)

        mockMvc.perform(post("/api/internal/stop/loop/phase/ITEM_EQUIPMENT"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("STOP_REQUESTED"))
            .andExpect(jsonPath("$.phase").value("ITEM_EQUIPMENT"))
            .andExpect(jsonPath("$.loopId").value("L-1"))
            .andExpect(jsonPath("$.iterationCount").value(3))
    }

    @Test
    fun `POST stop loop phase ITEM_EQUIPMENT while no loop returns 200 NOT_LOOPING`() {
        whenever(phaseLoopController.stopLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(null)

        mockMvc.perform(post("/api/internal/stop/loop/phase/ITEM_EQUIPMENT"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("NOT_LOOPING"))
            .andExpect(jsonPath("$.phase").value("ITEM_EQUIPMENT"))
    }

    @Test
    fun `POST stop loop phase RANKING_FETCH returns 400 INVALID_PHASE`() {
        mockMvc.perform(post("/api/internal/stop/loop/phase/RANKING_FETCH"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_PHASE"))
    }

    @Test
    fun `POST trigger phase ITEM_EQUIPMENT while loop active returns 409 LOOP_ACTIVE`() {
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(true)
        whenever(phaseLoopController.getLoopState(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(
            LoopState(loopId = "L-9", phase = PipelinePhase.ITEM_EQUIPMENT, startedAt = Instant.now()),
        )

        mockMvc.perform(
            post("/api/internal/trigger/phase/ITEM_EQUIPMENT").header("X-Upstream-Run-Id", "u-1"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("LOOP_ACTIVE"))
            .andExpect(jsonPath("$.phase").value("ITEM_EQUIPMENT"))
            .andExpect(jsonPath("$.loopId").value("L-9"))
    }

    @Test
    fun `POST trigger daily while any loop active returns 409 LOOP_ACTIVE for first looped phase`() {
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.RANKING_FETCH)).thenReturn(false)
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.OCID_LOOKUP)).thenReturn(false)
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.CHARACTER_BASIC)).thenReturn(true)
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(false)
        whenever(phaseLoopController.getLoopState(PipelinePhase.CHARACTER_BASIC)).thenReturn(
            LoopState(loopId = "L-CB", phase = PipelinePhase.CHARACTER_BASIC, startedAt = Instant.now()),
        )

        mockMvc.perform(post("/api/internal/trigger/daily"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("LOOP_ACTIVE"))
            .andExpect(jsonPath("$.loopId").value("L-CB"))
    }

    @Test
    fun `GET run-status with active loop decorates response with loopSummaries`() {
        val itemLoop = LoopState(
            loopId = "L-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.parse("2026-06-19T00:00:00Z"),
            iterationCount = 5,
            lastRunId = "run-5",
        )
        val itemSlot = RunStatus(
            runId = "run-5",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.parse("2026-06-19T00:01:00Z"),
            loopId = "L-1",
        )

        stubEmptyPerPhaseLookups()
        whenever(phaseLoopController.activeLoops()).thenReturn(listOf(itemLoop))
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(true)
        whenever(phaseLoopController.getLoopState(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(itemLoop)
        whenever(runStatusTracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(itemSlot)

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.loopSummaries.ITEM_EQUIPMENT.loopId").value("L-1"))
            .andExpect(jsonPath("$.loopSummaries.ITEM_EQUIPMENT.iterationCount").value(5))
            .andExpect(jsonPath("$.loopSummaries.ITEM_EQUIPMENT.status").value("RUNNING"))
            .andExpect(jsonPath("$.slots.ITEM_EQUIPMENT.loopId").value("L-1"))
    }

    @Test
    fun `GET run-status with no active loops returns empty loopSummaries`() {
        stubEmptyPerPhaseLookups()
        whenever(phaseLoopController.activeLoops()).thenReturn(emptyList())

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.loopSummaries").isMap)
            .andExpect(jsonPath("$.loopSummaries.ITEM_EQUIPMENT").doesNotExist())
    }
}
