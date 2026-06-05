package maple.expectation.infrastructure.concurrency

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

interface ThreadLauncher {
    fun launch(name: String, block: () -> Unit): Future<*>
}

class DefaultThreadLauncher(private val executor: ExecutorService) : ThreadLauncher {
    override fun launch(name: String, block: () -> Unit): Future<*> =
        executor.submit {
            Thread.currentThread().name = name
            block()
        }
}
