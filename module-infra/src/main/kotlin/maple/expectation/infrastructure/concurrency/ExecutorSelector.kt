package maple.expectation.infrastructure.concurrency

import java.util.concurrent.CompletableFuture

interface ExecutorSelector {
    fun <T> submit(qualifier: ExecutorQualifier, block: () -> T): CompletableFuture<T>
    fun shutdownAll(phase: ShutdownPhase)
}

class DefaultExecutorSelector(private val registry: ExecutorRegistry) : ExecutorSelector {
    override fun <T> submit(qualifier: ExecutorQualifier, block: () -> T): CompletableFuture<T> {
        val exec = registry.get(qualifier)
        return CompletableFuture.supplyAsync({ block() }, exec)
    }

    override fun shutdownAll(phase: ShutdownPhase) {
        // phase-based ordering deferred to Phase 2
    }
}
