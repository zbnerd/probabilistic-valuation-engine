package maple.externalapi.scheduler

import maple.externalapi.runstatus.PipelinePhase

/**
 * Thrown by phase beans when they detect a stop request at a chunk/page/batch
 * boundary. Caught specifically in `ExternalApiScheduler.runXxxPhase` `whenComplete`
 * handlers to drive a STOPPED terminal transition (vs. FAILED for other exceptions).
 */
class PhaseStoppedException(
    val phase: PipelinePhase,
) : RuntimeException("phase ${phase.name} stopped at chunk boundary")
