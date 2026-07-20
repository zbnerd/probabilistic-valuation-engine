package maple.nexon.client.transport

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import maple.nexon.client.config.ByokNexonClientProperties
import maple.nexon.client.config.NexonClientProfile
import maple.nexon.client.config.SystemNexonClientProperties
import maple.nexon.client.failure.NexonFailureClassifier
import maple.nexon.client.metrics.NexonClientMetrics
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test

class NexonTransportResourcesTest {
    @Test
    fun `stop callback follows deterministic disposal of both isolated providers`() {
        val metrics = NexonClientMetrics(SimpleMeterRegistry())
        val factory = NexonTransportFactory(
            classifier = NexonFailureClassifier(jacksonObjectMapper()),
            metrics = metrics,
            baseUrl = "http://127.0.0.1:1",
        )
        val system = factory.create(
            NexonClientProfile.SYSTEM_BULK,
            SystemNexonClientProperties(poolName = "test-system", metricsEnabled = false),
        )
        val byok = factory.create(
            NexonClientProfile.USER_BYOK,
            ByokNexonClientProperties(poolName = "test-byok", metricsEnabled = false),
        )
        val resources = NexonTransportResources(
            systemProvider = system.provider,
            byokProvider = byok.provider,
            shutdownTimeout = Duration.ofSeconds(2),
            metrics = metrics,
        )
        val callbacks = AtomicInteger()
        val stopped = CompletableFuture<Void>()
        resources.start()

        resources.stop(
            Runnable {
                callbacks.incrementAndGet()
                stopped.complete(null)
            },
        )
        await().until(stopped::isDone)

        assertThat(callbacks).hasValue(1)
        assertThat(resources.isRunning).isFalse()
        assertThat(system.provider.isDisposed).isTrue()
        assertThat(byok.provider.isDisposed).isTrue()
    }
}
