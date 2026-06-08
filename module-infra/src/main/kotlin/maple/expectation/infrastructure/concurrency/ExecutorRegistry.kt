package maple.expectation.infrastructure.concurrency

import java.util.concurrent.Executor

class ExecutorRegistry(private val executors: Map<ExecutorQualifier, Executor>) {
    fun get(qualifier: ExecutorQualifier): Executor = executors[qualifier]
        ?: throw IllegalArgumentException("No executor registered for $qualifier")
}
