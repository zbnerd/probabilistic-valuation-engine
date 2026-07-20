package maple.pipeline.messaging.adapter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import maple.pipeline.messaging.metrics.DeliveryMetrics
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test

class PipelineDeliveryExecutorsTest {
    @Test
    fun `owns named retry delivery and DLT resources and closes all three`() {
        val executors = PipelineDeliveryExecutors(DeliveryMetrics(SimpleMeterRegistry()))
        val retryThread = AtomicReference<Thread>()
        val deliveryThread = AtomicReference<Thread>()
        val dltThread = AtomicReference<Thread>()

        executors.retryScheduler.schedule({ retryThread.set(Thread.currentThread()) }, 0L, TimeUnit.MILLISECONDS)
        executors.deliveryExecutor.execute { deliveryThread.set(Thread.currentThread()) }
        executors.dltExecutor.execute { dltThread.set(Thread.currentThread()) }

        await().until { retryThread.getPlain() != null && deliveryThread.getPlain() != null && dltThread.getPlain() != null }
        assertThat(retryThread.getPlain().name).startsWith("pipeline-retry-")
        assertThat(deliveryThread.getPlain().name).startsWith("pipeline-delivery-")
        assertThat(deliveryThread.getPlain().isVirtual).isTrue()
        assertThat(dltThread.getPlain().name).startsWith("pipeline-dlt-")
        assertThat(dltThread.getPlain().isVirtual).isTrue()
        assertThat(deliveryThread.getPlain()).isNotSameAs(dltThread.getPlain())

        executors.close()

        assertThat(executors.retryScheduler.isShutdown).isTrue()
        assertThat(executors.deliveryExecutor.isShutdown).isTrue()
        assertThat(executors.dltExecutor.isShutdown).isTrue()
    }
}
