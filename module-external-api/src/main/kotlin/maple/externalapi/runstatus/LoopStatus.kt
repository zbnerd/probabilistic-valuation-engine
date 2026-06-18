package maple.externalapi.runstatus

/**
 * Lifecycle state of a phase loop.
 *  - RUNNING: at least one iteration has been submitted; loop is active.
 *  - STOPPING: a stop was requested or an iteration failed; current iteration
 *    may still be in-flight, but no new iteration will be submitted.
 *  - STOPPED: terminal; controller has called finalize.
 */
enum class LoopStatus {
    RUNNING,
    STOPPING,
    STOPPED,
}
