package maple.expectation.infrastructure.concurrency

import org.springframework.beans.factory.DisposableBean

interface LifecycleComponent : DisposableBean {
    fun componentName(): String
    suspend fun drain()

    override fun destroy() {
        kotlinx.coroutines.runBlocking { drain() }
    }

    fun shutdownTimeoutMs(): Long = 5_000L
}
