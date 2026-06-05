package maple.expectation.infrastructure.concurrency

import java.util.concurrent.Executor
import java.util.concurrent.Future

interface ThreadLauncher {
    fun launch(name: String, block: () -> Unit): Future<*>
}

class DefaultThreadLauncher(private val executor: Executor) : ThreadLauncher {
    override fun launch(name: String, block: () -> Unit): Future<*> =
        when (executor) {
            is java.util.concurrent.ExecutorService -> executor.submit {
                Thread.currentThread().name = name
                block()
            }
            else -> throw IllegalArgumentException("ThreadLauncher requires ExecutorService, got ${executor::class}")
        }
}
