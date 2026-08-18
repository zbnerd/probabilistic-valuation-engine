package maple.pipeline.artifact.storage

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import maple.expectation.util.InterruptUtils

class ArtifactUploadResources internal constructor(
    val executor: ExecutorService,
    meterRegistry: MeterRegistry?,
) : AutoCloseable {
    constructor(meterRegistry: MeterRegistry?) : this(newUploadExecutor(), meterRegistry)

    private val closed = AtomicBoolean(false)
    private val forcedShutdownCounter: Counter? = meterRegistry?.let { registry ->
        Counter.builder("pipeline.artifact.executor.forced.shutdown")
            .tag("executor", "upload")
            .register(registry)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdown()
        val terminated = runCatching { executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS) }
            .onFailure(InterruptUtils::restoreInterruptIfNeeded)
            .getOrDefault(false)
        if (!terminated) {
            executor.shutdownNow()
            forcedShutdownCounter?.increment()
        }
    }

    private companion object {
        const val SHUTDOWN_SECONDS = 5L

        fun newUploadExecutor(): ExecutorService = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("artifact-upload-", 0).factory(),
        )
    }
}
