package maple.externalapi.loop

import java.time.Clock
import java.util.concurrent.CompletableFuture
import maple.externalapi.runstatus.LoopStatus
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.ExternalApiScheduler
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.phase.RunIdGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.task.AsyncTaskExecutor

class PhaseLoopControllerTest {

    private val runStatusTracker = RunStatusTracker(Clock.systemUTC())
    private val scheduler = mock<ExternalApiScheduler>()
    private val stopSignal = PhaseStopSignal()
    private val runIdGenerator = RunIdGenerator(Clock.systemUTC())
    // No-op executor for tests that verify single submit via scheduler mock.
    private val noopExecutor = AsyncTaskExecutor { /* drop submitted Runnable */ }
    // Runs the first submission inline, drops the rest.
    private val oneShotInlineExecutor = OneShotInlineExecutor()

    private fun controller(executor: AsyncTaskExecutor = noopExecutor) = PhaseLoopController(
        externalApiScheduler = scheduler,
        runStatusTracker = runStatusTracker,
        runIdGenerator = runIdGenerator,
        stopSignal = stopSignal,
        loopExecutor = executor,
    )

    @Test
    fun `startLoop on ITEM_EQUIPMENT returns LoopState with RUNNING status and submits first iteration`() {
        whenever(scheduler.triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture<Void>().also { it.complete(null) })

        val state = controller().startLoop(PipelinePhase.ITEM_EQUIPMENT)

        assertEquals(PipelinePhase.ITEM_EQUIPMENT, state.phase)
        assertEquals(LoopStatus.RUNNING, state.status)
        assertNotNull(state.loopId)
        assertThat(state.loopId).hasSizeGreaterThan(0)
        verify(scheduler).triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `startLoop on duplicate phase returns existing state without resubmit`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture<Void>().also { it.complete(null) })

        val ctrl = controller()
        val first = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        val second = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)

        assertEquals(first.loopId, second.loopId)
        verify(scheduler, org.mockito.kotlin.times(1))
            .triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `startLoop rejects non-loopable phase with IllegalArgumentException`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            controller().startLoop(PipelinePhase.RANKING_FETCH)
        }
        assertTrue(ex.message!!.contains("RANKING_FETCH"))
    }

    @Test
    fun `hasActiveLoop true while RUNNING, false after STOPPED`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture<Void>().also { it.complete(null) })

        val ctrl = controller()
        assertFalse(ctrl.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT))
        ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        assertTrue(ctrl.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT))

        val state = ctrl.getLoopState(PipelinePhase.ITEM_EQUIPMENT)!!
        state.status = LoopStatus.STOPPED
        assertFalse(ctrl.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `getLoopState returns null for phase with no active loop`() {
        assertNull(controller().getLoopState(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `startLoop returns iterationCount=0 and lastRunId null`() {
        // Pending future: keeps whenComplete from firing inline, so we observe
        // the state right after startLoop returns, before any iteration completes.
        whenever(scheduler.triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture<Void>())

        val state = controller().startLoop(PipelinePhase.ITEM_EQUIPMENT)
        assertEquals(0, state.iterationCount)
        assertNull(state.lastRunId)
        verify(scheduler).triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `handleIterationEnd increments iterationCount on success`() {
        whenever(scheduler.triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val state = controller(oneShotInlineExecutor).startLoop(PipelinePhase.ITEM_EQUIPMENT)
        assertEquals(2, state.iterationCount)
        assertNotNull(state.lastRunId)
    }

    @Test
    fun `successful iteration chains into next iteration via loopExecutor`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val ctrl = controller(oneShotInlineExecutor)
        ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)

        verify(scheduler, org.mockito.kotlin.times(2))
            .triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `stopLoop on active loop sets stopSignal and returns existing state`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture<Void>().also { it.complete(null) })

        val ctrl = controller()
        val state = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        val stopped = ctrl.stopLoop(PipelinePhase.ITEM_EQUIPMENT)

        assertEquals(state.loopId, stopped!!.loopId)
        assertTrue(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `stopLoop on no active loop returns null`() {
        assertNull(controller().stopLoop(PipelinePhase.ITEM_EQUIPMENT))
        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `finalize clears stopSignal and transitions status to STOPPED`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture<Void>().also { it.complete(null) })

        val ctrl = controller()
        val state = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        stopSignal.requestStop(PipelinePhase.ITEM_EQUIPMENT)

        state.status = LoopStatus.STOPPING
        val finalizeRef = PhaseLoopController::class.java
            .getDeclaredMethod("finalize", PipelinePhase::class.java, String::class.java)
            .apply { isAccessible = true }
        finalizeRef.invoke(ctrl, PipelinePhase.ITEM_EQUIPMENT, state.loopId)

        assertEquals(LoopStatus.STOPPED, state.status)
        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `shutdown transitions all active loops to STOPPED and clears stopSignal`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture<Void>().also { it.complete(null) })

        val ctrl = controller()
        val ieState = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        val cbState = ctrl.startLoop(PipelinePhase.CHARACTER_BASIC)

        assertTrue(ctrl.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT))
        assertTrue(ctrl.hasActiveLoop(PipelinePhase.CHARACTER_BASIC))

        ctrl.shutdown()

        // shutdown() trips signal AND calls finalize(), which clears the signal.
        // So after shutdown, signal is cleared and state is STOPPED.
        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
        assertFalse(stopSignal.isStopRequested(PipelinePhase.CHARACTER_BASIC))
        assertEquals(LoopStatus.STOPPED, ieState.status)
        assertEquals(LoopStatus.STOPPED, cbState.status)
        val fresh = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        assertNotEquals(ieState.loopId, fresh.loopId, "shutdown must leave state clean for fresh startLoop")
    }

    @Test
    fun `concurrent startLoop on same phase — only one wins`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture<Void>().also { it.complete(null) })

        val ctrl = controller()
        val states = (1..10).map {
            CompletableFuture.supplyAsync { ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT) }
        }.map { it.join() }

        val distinctLoopIds = states.map { it.loopId }.toSet()
        assertEquals(1, distinctLoopIds.size, "all concurrent startLoop calls must share one loopId")
    }

    private class OneShotInlineExecutor : AsyncTaskExecutor {
        private var used = false
        override fun execute(task: Runnable) {
            if (used) return
            used = true
            task.run()
        }
    }
}
