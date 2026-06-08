package maple.expectation.infrastructure.concurrency

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.slf4j.LoggerFactory

interface AsyncGuard {
    fun <T> guard(name: String, timeoutMs: Long, chain: CompletableFuture<T>): CompletableFuture<T>
}

class DefaultAsyncGuard : AsyncGuard {
    private val log = LoggerFactory.getLogger(DefaultAsyncGuard::class.java)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "async-guard-scheduler").apply { isDaemon = true }
    }

    override fun <T> guard(name: String, timeoutMs: Long, chain: CompletableFuture<T>): CompletableFuture<T> {
        val guarded = CompletableFuture<T>()

        chain.whenComplete { result, ex ->
            if (ex != null) {
                guarded.completeExceptionally(ex)
            } else {
                guarded.complete(result)
            }
        }

        scheduler.schedule<Unit>({
            if (!guarded.isDone) {
                log.warn("AsyncGuard timeout: chain '$name' exceeded ${timeoutMs}ms")
                guarded.completeExceptionally(TimeoutException("AsyncGuard: $name exceeded ${timeoutMs}ms"))
            }
        }, timeoutMs, TimeUnit.MILLISECONDS)

        return guarded
    }
}
