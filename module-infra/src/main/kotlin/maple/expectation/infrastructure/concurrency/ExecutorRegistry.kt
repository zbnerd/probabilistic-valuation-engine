package maple.expectation.infrastructure.concurrency

import java.util.concurrent.ExecutorService

class ExecutorRegistry(private val executors: Map<ExecutorQualifier, ExecutorService>) {
    fun get(qualifier: ExecutorQualifier): ExecutorService =
        executors[qualifier]
            ?: throw IllegalArgumentException("No executor registered for $qualifier")
}
